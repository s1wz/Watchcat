package dev.watchcat.capture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PrologueBuilderTest {
    static final class Profile {
        Profile(UUID id, String name) {}
    }

    static final class ProfileOnly {
        ProfileOnly(Profile profile) {}
    }

    static final class ProfileAndSession {
        ProfileAndSession(Profile profile, UUID sessionId) {}
    }

    static final class BothShapes {
        BothShapes(Profile profile, UUID sessionId) {}
        BothShapes(Profile profile) {}
    }

    static final class WithFlag {
        WithFlag(Profile profile, boolean strictErrorHandling) {}
    }

    static final class NoProfile {
        NoProfile(UUID id) {}
    }

    static final class Unsatisfiable {
        Unsatisfiable(Profile profile, int protocolVersion) {}
    }

    private static int selfEntityId(byte[] raw, int varIntLength) {
        return ByteBuffer.wrap(raw, varIntLength, Integer.BYTES).getInt();
    }

    @Test
    void rewritesTheSelfEntityIdAfterASingleBytePacketId() {
        byte[] raw = {0x31, 0x00, 0x00, 0x00, 0x01, (byte) 0xAB, (byte) 0xCD};

        byte[] out = PrologueBuilder.withPhantomSelf(raw);

        assertEquals(PrologueBuilder.PHANTOM_SELF_ENTITY_ID, selfEntityId(out, 1));
        assertEquals(0x31, out[0], "packet id must be preserved");
        assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD},
                new byte[]{out[5], out[6]}, "the rest of the payload must be untouched");
    }

    @Test
    void rewritesTheSelfEntityIdAfterAMultiByteVarIntPacketId() {
        byte[] raw = {(byte) 0x80, 0x02, 0x00, 0x00, 0x00, 0x07, 0x5A};

        byte[] out = PrologueBuilder.withPhantomSelf(raw);

        assertEquals(PrologueBuilder.PHANTOM_SELF_ENTITY_ID, selfEntityId(out, 2));
        assertEquals(0x5A, out[6], "the rest of the payload must be untouched");
    }

    @Test
    void doesNotMutateTheInput() {
        byte[] raw = {0x31, 0x00, 0x00, 0x00, 0x01};

        byte[] out = PrologueBuilder.withPhantomSelf(raw);

        assertNotSame(raw, out);
        assertEquals(1, selfEntityId(raw, 1), "the captured packet must stay pristine");
    }

    @Test
    void leavesTooShortPayloadsAlone() {
        byte[] raw = {0x31, 0x00, 0x00};

        assertSame(raw, PrologueBuilder.withPhantomSelf(raw));
    }

    @Test
    void acceptsALoginPacketThatOnlyCarriesAProfile() {
        Constructor<?> chosen = PrologueBuilder.packetConstructor(ProfileOnly.class, Profile.class);

        assertNotNull(chosen, "a (GameProfile) constructor must be usable");
        assertEquals(1, chosen.getParameterCount());
    }

    @Test
    void acceptsALoginPacketThatAlsoCarriesASessionId() {
        Constructor<?> chosen = PrologueBuilder.packetConstructor(ProfileAndSession.class, Profile.class);

        assertNotNull(chosen, "a (GameProfile, UUID) constructor must be usable");
        assertEquals(2, chosen.getParameterCount());
    }

    @Test
    void prefersTheShortestUsableConstructor() {
        Constructor<?> chosen = PrologueBuilder.packetConstructor(BothShapes.class, Profile.class);

        assertNotNull(chosen);
        assertEquals(1, chosen.getParameterCount());
    }

    @Test
    void rejectsConstructorsWithoutAProfile() {
        assertNull(PrologueBuilder.packetConstructor(NoProfile.class, Profile.class));
    }

    @Test
    void rejectsConstructorsWithArgumentsItCannotInvent() {
        assertNull(PrologueBuilder.packetConstructor(Unsatisfiable.class, Profile.class));
    }

    @Test
    void fillsTheProfileAndDefaultsEveryOtherArgument() throws Exception {
        Object profile = new Profile(UUID.randomUUID(), "sjwz");

        Constructor<?> withSession = PrologueBuilder.packetConstructor(ProfileAndSession.class, Profile.class);
        Object[] sessionArgs = PrologueBuilder.argumentsFor(withSession, Profile.class, profile);

        assertSame(profile, sessionArgs[0]);
        assertEquals(new UUID(0L, 0L), sessionArgs[1]);
        assertNotNull(withSession.newInstance(sessionArgs));

        Constructor<?> withFlag = PrologueBuilder.packetConstructor(WithFlag.class, Profile.class);
        Object[] flagArgs = PrologueBuilder.argumentsFor(withFlag, Profile.class, profile);

        assertSame(profile, flagArgs[0]);
        assertEquals(Boolean.FALSE, flagArgs[1]);
        assertNotNull(withFlag.newInstance(flagArgs));
    }
}

package app.daiyu.android.vd.runtime;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/** Pure JVM, offline contract vectors. No owner, display, socket, shell or Android runtime. */
public class OwnerOperationIdentityTest {
    static OwnerOperationIdentity.RunBinding binding() {
        return OwnerOperationIdentity.bindingFromTrustedHost("s", "t", "c", "r", "m", "o");
    }

    static OwnerOperationIdentity identity(Map<String, ?> args) {
        return new OwnerOperationIdentity("gen_1", "op_1", "launch", binding(), args);
    }

    @Test public void exactCanonicalVectorAndKnownSha256Vector() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("z", Arrays.asList(null, true, new BigDecimal("1.2300"), new BigDecimal("-0.00")));
        value.put("a", "quote\" slash\\ newline\n é 😀");
        assertEquals("{\"a\":\"quote\\\" slash\\\\ newline\\u000a é 😀\",\"z\":[null,true,1.23,0]}",
                OwnerOperationIdentity.canonicalJson(value));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                OwnerOperationIdentity.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void fullEnvelopeVectorIncludesAllBindingFieldsAndEffectiveParameters() {
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("flags", Arrays.asList(3, 1));
        parameters.put("component", "pkg/.Activity");
        String expected = "{\"contract\":\"owner-operation-v1\",\"generation\":\"gen_1\","
                + "\"operation\":\"launch\",\"operationId\":\"op_1\","
                + "\"parameters\":{\"component\":\"pkg/.Activity\",\"flags\":[3,1]},"
                + "\"runBinding\":{\"assistantMessage\":\"m\",\"conversation\":\"c\","
                + "\"run\":\"r\",\"runOwner\":\"o\",\"session\":\"s\",\"task\":\"t\"}}";
        assertEquals(OwnerOperationIdentity.sha256(expected.getBytes(StandardCharsets.UTF_8)),
                identity(parameters).digest);
        assertEquals(64, identity(parameters).digest.length());
    }

    @Test public void orderingNormalizedButArrayOrderAndUnknownBusinessFieldsAreNotDropped() {
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("z", Arrays.asList(1, 2)); a.put("a", new BigDecimal("1.0"));
        Map<String, Object> b = new TreeMap<String, Object>(a);
        b.put("a", 1L);
        assertEquals(identity(a).digest, identity(b).digest);
        b.put("extra", "must participate");
        assertNotEquals(identity(a).digest, identity(b).digest);
        b.remove("extra"); b.put("z", Arrays.asList(2, 1));
        assertNotEquals(identity(a).digest, identity(b).digest);
        b.put("z", Arrays.asList(1, 2)); b.put("optional", null);
        assertNotEquals(identity(a).digest, identity(b).digest);
    }

    @Test public void everyBindingDimensionAndIdentityDimensionChangesDigest() {
        String[] base = {"s", "t", "c", "r", "m", "o"};
        Map<String, Object> parameters = new HashMap<String, Object>();
        OwnerOperationIdentity original = identity(parameters);
        for (int i = 0; i < base.length; i++) {
            String[] fields = base.clone(); fields[i] += "changed";
            OwnerOperationIdentity.RunBinding changed = OwnerOperationIdentity.bindingFromTrustedHost(
                    fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
            OwnerOperationIdentity next = new OwnerOperationIdentity("gen_1", "op_1", "launch", changed, parameters);
            assertNotEquals(original.digest, next.digest);
            assertNotEquals(original.bindingDigest, next.bindingDigest);
        }
        assertNotEquals(original.digest, new OwnerOperationIdentity("gen_2", "op_1", "launch", binding(), parameters).digest);
        assertNotEquals(original.digest, new OwnerOperationIdentity("gen_1", "op_2", "launch", binding(), parameters).digest);
        assertNotEquals(original.digest, new OwnerOperationIdentity("gen_1", "op_1", "input", binding(), parameters).digest);
    }

    @Test public void bindingHasNoPublicConstructorAndModelNamedBindingCannotOverrideIt() {
        for (Constructor<?> constructor : OwnerOperationIdentity.RunBinding.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        Map<String, Object> args = new HashMap<String, Object>();
        OwnerOperationIdentity before = identity(args);
        args.put("runBinding", "forged");
        OwnerOperationIdentity after = identity(args);
        assertEquals(before.bindingDigest, after.bindingDigest);
        assertNotEquals(before.digest, after.digest);
        invalid(() -> new OwnerOperationIdentity("gen_1", "op_1", "launch", null, args));
    }

    @Test public void identityDoesNotRetainMutableParameterReferences() {
        Map<String, Object> args = new HashMap<String, Object>();
        List<Object> nested = new ArrayList<Object>(); nested.add(1);
        args.put("nested", nested);
        OwnerOperationIdentity before = identity(args);
        String digest = before.digest;
        nested.add(2); args.put("new", true);
        assertEquals(digest, before.digest);
        assertNotEquals(digest, identity(args).digest);
    }

    @Test public void safeIdsHaveExplicitBoundaryAndRejectTraversalOrEncodedSeparators() {
        String max = repeat('a', OwnerOperationIdentity.MAX_ID_CHARS);
        assertEquals(max, OwnerOperationIdentity.pathId(max));
        for (String bad : new String[]{"", ".", "..", "../x", "/x", "a/b", "a\\b", "a.b", "x%2fy",
                "a\n", "a b", "é", repeat('a', OwnerOperationIdentity.MAX_ID_CHARS + 1)}) {
            invalid(() -> OwnerOperationIdentity.pathId(bad));
            invalid(() -> new OwnerOperationIdentity(bad, "op", "launch", binding(), new HashMap<String, Object>()));
            invalid(() -> new OwnerOperationIdentity("gen", bad, "launch", binding(), new HashMap<String, Object>()));
        }
    }

    @Test public void bindingLimitsAndInvalidUnicodeAreRejected() {
        invalid(() -> OwnerOperationIdentity.bindingFromTrustedHost("", "t", "c", "r", "m", "o"));
        invalid(() -> OwnerOperationIdentity.bindingFromTrustedHost(repeat('s', 257), "t", "c", "r", "m", "o"));
        invalid(() -> OwnerOperationIdentity.bindingFromTrustedHost("s\n", "t", "c", "r", "m", "o"));
        invalid(() -> OwnerOperationIdentity.canonicalJson("\ud800"));
        invalid(() -> OwnerOperationIdentity.canonicalJson("\udc00"));
        assertNotEquals(OwnerOperationIdentity.canonicalJson("é"), OwnerOperationIdentity.canonicalJson("e\u0301"));
    }

    @Test public void lossyUnsupportedAmbiguousCyclicAndOversizeValuesFailClosed() {
        for (Object unsupported : new Object[]{1.0d, Float.NaN, Double.POSITIVE_INFINITY,
                new Object(), new int[]{1, 2}, new BigDecimal("1e1000000")}) {
            invalid(() -> OwnerOperationIdentity.canonicalJson(unsupported));
        }
        Map<Object, Object> nonString = new HashMap<Object, Object>(); nonString.put(1, true);
        invalid(() -> OwnerOperationIdentity.canonicalJson(nonString));
        Map<String, Object> duplicate = new IdentityHashMap<String, Object>();
        duplicate.put(new String("same"), 1); duplicate.put(new String("same"), 2);
        invalid(() -> OwnerOperationIdentity.canonicalJson(duplicate));
        List<Object> cycle = new ArrayList<Object>(); cycle.add(cycle);
        invalid(() -> OwnerOperationIdentity.canonicalJson(cycle));
        Object deep = null;
        for (int i = 0; i < 34; i++) deep = Arrays.asList(deep);
        final Object tooDeep = deep;
        invalid(() -> OwnerOperationIdentity.canonicalJson(tooDeep));
        invalid(() -> OwnerOperationIdentity.canonicalJson(repeat('x', 65537)));
        invalid(() -> OwnerOperationIdentity.canonicalJson(repeat('é', 40000)));
        invalid(() -> OwnerOperationIdentity.canonicalJson(Arrays.asList(new Object[8193])));
    }

    private static String repeat(char c, int length) {
        char[] chars = new char[length]; Arrays.fill(chars, c); return new String(chars);
    }

    private static void invalid(Runnable call) {
        try { call.run(); fail("expected invalid canonical/identity input"); }
        catch (IllegalArgumentException expected) {
            assertEquals("invalid owner identity/canonical value", expected.getMessage());
        }
    }
}

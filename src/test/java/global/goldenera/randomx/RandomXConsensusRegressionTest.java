/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2022-2030 The XdagJ Developers
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package global.goldenera.randomx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.sun.jna.Pointer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RandomXConsensusRegressionTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final int RANDOMX_V2_FLAG = 128;
    // Generated before the upgrade from wrapper 68e000da, RandomX 10494476 and
    // Linux x86_64 native SHA-256 00cbfc78bc981422dabed2fa228a0142e9a32a85755a45af386303927e726659.
    private static final byte[] BASELINE_KEY =
            "GoldenEra RandomX v1 consensus regression".getBytes(StandardCharsets.UTF_8);
    private static final List<byte[]> BASELINE_INPUTS = List.of(
            new byte[0],
            new byte[] { 0 },
            sequence(32, 1, 1),
            sequence(76, 11, 37),
            sequence(257, 0xa5, 73));
    private static final List<String> BASELINE_HASHES = List.of(
            "23366ef7ddff4883984a2c156afbcee5e3b1f57422ff21e534f4b47b8b94cb85",
            "403c1fd248068b06d36fa28299fc3ec9e8cc9f22fecbfe0396c74c59a64fb2ff",
            "32c3bd8ec6bca4696e615d8caf82289c2bacde69b9eb737499d8ee105cf553f4",
            "eb8225c4a22fdc013c32d6338a19c074390da2b1cbd82070722a7a21f3a98d9c",
            "3739b42b6122a1795913aa13301d085510f2875caa753899e5701e137f452631");
    private static final String BASELINE_COMMITMENT =
            "0fe2b30009d545e94a11bfacd8b965fffa56752f5deed830019d19478680346d";
    private static final byte[] ISUB_R_KEY = HEX.parseHex(
            "7797373ea4633194640bf8d8c3b66724d6aa7bd2dc20e009df2f8f1710abe8");
    private static final byte[] ISUB_R_INPUT = HEX.parseHex(
            "1010e1eaf8cf067b37b5f0ee031ab23ed1755e090a3af4415830145853e2be3e1"
                    + "f6821fed84dae58d00e00da5214d6c1f2d0622e0abd51f9373d04e0b0f8e6d6514d90689721c4aac5a9bb0d");
    private static final String ISUB_R_HASH =
            "78af2a1864c42abce36d2e8983e13df99b2af0ce1362999af09fab004d4435a8";

    private Set<RandomXFlag> lightFlags;
    private RandomXCache cache;
    private RandomXVM vm;

    @BeforeAll
    void createBaselineVm() {
        lightFlags = lightFlags();
        assertTrue(lightFlags.contains(RandomXFlag.JIT),
                "Supported release builds must exercise the RandomX JIT path.");
        cache = new RandomXCache(lightFlags);
        cache.init(BASELINE_KEY);
        vm = new RandomXVM(lightFlags, cache, null);
    }

    @AfterAll
    void closeBaselineVm() {
        if (vm != null) {
            vm.close();
        }
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void nativeCapabilitiesKeepRandomXV2Disabled() {
        int nativeFlags = RandomXUtils.getNativeFlags();
        int wrapperFlags = RandomXFlag.toValue(RandomXUtils.getRecommendedFlags());

        assertEquals(0, nativeFlags & RANDOMX_V2_FLAG,
                "The native capability probe must not opt GoldenEra into RandomX v2.");
        assertEquals(0, wrapperFlags & RANDOMX_V2_FLAG,
                "The Java wrapper must keep the consensus VM on RandomX v1.");
        assertEquals(Set.of(), RandomXFlag.fromValue(RANDOMX_V2_FLAG));
        assertFalse(Arrays.stream(RandomXFlag.values())
                .anyMatch(flag -> flag.getValue() == RANDOMX_V2_FLAG));
    }

    @Test
    void cacheMemoryApiRemainsAvailable() {
        Pointer memory = RandomXNative.randomx_get_cache_memory(cache.getCachePointer());

        assertNotNull(memory);
        assertNotEquals(0L, Pointer.nativeValue(memory));
    }

    @Test
    void singleHashResultsMatchPreUpgradeBaseline() {
        assertEquals(BASELINE_HASHES, calculateSingleHashes(vm, BASELINE_INPUTS));
    }

    @Test
    void interpreterResultsMatchPreUpgradeBaseline() {
        Set<RandomXFlag> interpreterFlags = Set.of(RandomXFlag.DEFAULT);
        try (RandomXCache interpreterCache = new RandomXCache(interpreterFlags)) {
            interpreterCache.init(BASELINE_KEY);
            try (RandomXVM interpreterVm = new RandomXVM(interpreterFlags, interpreterCache, null)) {
                assertEquals(BASELINE_HASHES, calculateSingleHashes(interpreterVm, BASELINE_INPUTS));
            }
        }
    }

    @Test
    void callerOwnedOutputMatchesPreUpgradeBaseline() {
        byte[] output = new byte[RandomXUtils.RANDOMX_HASH_SIZE + 8];
        Arrays.fill(output, (byte) 0x5a);

        vm.calculateHashInto(BASELINE_INPUTS.get(3), output, 4);

        assertEquals(BASELINE_HASHES.get(3),
                HEX.formatHex(Arrays.copyOfRange(output, 4, 4 + RandomXUtils.RANDOMX_HASH_SIZE)));
        assertArrayEquals(new byte[] { 0x5a, 0x5a, 0x5a, 0x5a }, Arrays.copyOfRange(output, 0, 4));
        assertArrayEquals(new byte[] { 0x5a, 0x5a, 0x5a, 0x5a },
                Arrays.copyOfRange(output, output.length - 4, output.length));
    }

    @Test
    void pipelineResultsMatchSingleHashAndPreUpgradeBaseline() {
        List<String> pipelineHashes = new ArrayList<>(BASELINE_INPUTS.size());
        vm.calculateHashFirst(BASELINE_INPUTS.get(0));
        for (int i = 1; i < BASELINE_INPUTS.size(); i++) {
            pipelineHashes.add(HEX.formatHex(vm.calculateHashNext(BASELINE_INPUTS.get(i))));
        }
        pipelineHashes.add(HEX.formatHex(vm.calculateHashLast()));

        assertEquals(BASELINE_HASHES, pipelineHashes);
    }

    @Test
    void commitmentMatchesPreUpgradeBaseline() {
        byte[] input = BASELINE_INPUTS.get(3);
        byte[] hash = vm.calculateHash(input);

        assertEquals(BASELINE_COMMITMENT, HEX.formatHex(vm.calculateCommitment(input, hash)));
    }

    @Test
    void isubREdgeCaseMatchesCanonicalV1Hash() {
        try (RandomXCache edgeCache = new RandomXCache(lightFlags)) {
            edgeCache.init(ISUB_R_KEY);
            try (RandomXVM edgeVm = new RandomXVM(lightFlags, edgeCache, null)) {
                assertEquals(ISUB_R_HASH, HEX.formatHex(edgeVm.calculateHash(ISUB_R_INPUT)));
            }
        }
    }

    @Test
    void lightAndFullModesProduceTheSameCanonicalV1Hash() {
        EnumSet<RandomXFlag> fullFlags = EnumSet.copyOf(lightFlags);
        fullFlags.add(RandomXFlag.FULL_MEM);
        byte[] input = BASELINE_INPUTS.get(3);
        byte[] lightHash = vm.calculateHash(input);

        try (RandomXCache fullCache = new RandomXCache(fullFlags);
                RandomXDataset dataset = new RandomXDataset(fullFlags)) {
            fullCache.init(BASELINE_KEY);
            dataset.init(fullCache);
            try (RandomXVM fullVm = new RandomXVM(fullFlags, fullCache, dataset)) {
                byte[] fullHash = fullVm.calculateHash(input);

                assertArrayEquals(lightHash, fullHash);
                assertEquals(BASELINE_HASHES.get(3), HEX.formatHex(fullHash));
            }
        }
    }

    private static List<String> calculateSingleHashes(RandomXVM target, List<byte[]> inputs) {
        return inputs.stream()
                .map(target::calculateHash)
                .map(HEX::formatHex)
                .toList();
    }

    private static Set<RandomXFlag> lightFlags() {
        EnumSet<RandomXFlag> flags = EnumSet.copyOf(RandomXUtils.getRecommendedFlags());
        flags.remove(RandomXFlag.FULL_MEM);
        flags.remove(RandomXFlag.LARGE_PAGES);
        if (flags.isEmpty()) {
            flags.add(RandomXFlag.DEFAULT);
        }
        assertFalse(flags.contains(RandomXFlag.FULL_MEM));
        return Set.copyOf(flags);
    }

    private static byte[] sequence(int length, int start, int step) {
        byte[] result = new byte[length];
        int value = start;
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) value;
            value += step;
        }
        return result;
    }
}

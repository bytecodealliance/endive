package endive.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.api.NativeMachineFactoryProvider;

class RedlinePanamaE2eTest {

    @Test
    public void panamaProviderIsSelected() {
        var provider = NativeMachineFactoryProvider.discover();
        assertTrue(provider.isPresent(), "Should discover a native provider");
        assertEquals(100, provider.get().priority(), "Panama should win with priority 100");
    }

    @Test
    public void nativeBuilderProducesCorrectResults() {
        try (var instance = AddModule.builder().build()) {
            var add = instance.export("add");
            assertArrayEquals(new long[] {3}, add.apply(1, 2));
            assertArrayEquals(new long[] {0}, add.apply(0, 0));
            assertEquals(
                    (int) add.apply(0, -1)[0],
                    -1,
                    "i32 add(0, -1) should be -1 when narrowed to int");
        }
    }

    @Test
    public void nativeCodeIsAvailable() {
        assertNotNull(
                AddModule.loadNativeCode(), "Native code should be available on this platform");
    }

    @Test
    public void bothBuildersProduceSameResults() {
        try (var nativeInstance = AddModule.builder().build();
                var safeInstance = AddModule.safeBuilder().build()) {
            var nativeAdd = nativeInstance.export("add");
            var safeAdd = safeInstance.export("add");

            for (int a = -10; a <= 10; a++) {
                for (int b = -10; b <= 10; b++) {
                    assertEquals(
                            (int) safeAdd.apply(a, b)[0],
                            (int) nativeAdd.apply(a, b)[0],
                            "add(" + a + ", " + b + ") should match");
                }
            }
        }
    }
}

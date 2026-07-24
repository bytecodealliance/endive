package endive.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class RedlineE2eTest {

    @Test
    public void nativeBuilderProducesCorrectResults() {
        try (var instance = AddModule.builder().build()) {
            var add = instance.export("add");
            assertArrayEquals(new long[] {3}, add.apply(1, 2));
            assertArrayEquals(new long[] {0}, add.apply(0, 0));
            assertArrayEquals(new long[] {-1}, add.apply(0, -1));
        }
    }

    @Test
    public void safeBuilderProducesCorrectResults() {
        try (var instance = AddModule.safeBuilder().build()) {
            var add = instance.export("add");
            assertArrayEquals(new long[] {3}, add.apply(1, 2));
            assertArrayEquals(new long[] {0}, add.apply(0, 0));
            assertArrayEquals(new long[] {-1}, add.apply(0, -1));
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
                    assertArrayEquals(
                            safeAdd.apply(a, b),
                            nativeAdd.apply(a, b),
                            "add(" + a + ", " + b + ") should match");
                }
            }
        }
    }
}

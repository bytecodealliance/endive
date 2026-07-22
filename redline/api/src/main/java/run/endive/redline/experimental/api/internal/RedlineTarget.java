package run.endive.redline.experimental.api.internal;

import java.util.Locale;
import java.util.Optional;

public enum RedlineTarget {
    LINUX_X86_64("x86_64-unknown-linux-gnu", "x86_64-linux"),
    LINUX_AARCH64("aarch64-unknown-linux-gnu", "aarch64-linux"),
    MACOS_X86_64("x86_64-apple-darwin", "x86_64-darwin"),
    MACOS_AARCH64("aarch64-apple-darwin", "aarch64-darwin"),
    WINDOWS_X86_64("x86_64-pc-windows-msvc", "x86_64-windows"),
    WINDOWS_AARCH64("aarch64-pc-windows-msvc", "aarch64-windows");

    private final String triple;
    private final String resourceSuffix;

    RedlineTarget(String triple, String resourceSuffix) {
        this.triple = triple;
        this.resourceSuffix = resourceSuffix;
    }

    public String triple() {
        return triple;
    }

    public String resourceSuffix() {
        return resourceSuffix;
    }

    public static Optional<RedlineTarget> detectHost() {
        String osName =
                System.getProperty("endive.redline.os.name", System.getProperty("os.name", ""))
                        .toLowerCase(Locale.ROOT);
        String arch =
                System.getProperty("endive.redline.os.arch", System.getProperty("os.arch", ""))
                        .toLowerCase(Locale.ROOT);

        boolean isAarch64 = arch.equals("aarch64") || arch.equals("arm64");

        if (osName.contains("linux")) {
            return Optional.of(isAarch64 ? LINUX_AARCH64 : LINUX_X86_64);
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return Optional.of(isAarch64 ? MACOS_AARCH64 : MACOS_X86_64);
        } else if (osName.contains("windows")) {
            return Optional.of(isAarch64 ? WINDOWS_AARCH64 : WINDOWS_X86_64);
        }
        return Optional.empty();
    }
}

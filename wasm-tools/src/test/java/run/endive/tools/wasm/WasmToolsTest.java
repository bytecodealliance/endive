package run.endive.tools.wasm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiExitException;
import run.endive.wasm.Parser;

public class WasmToolsTest {

    @Test
    public void shouldRunWast2Json(@TempDir Path tempDir) throws Exception {
        // Arrange
        var outputFile = tempDir.resolve("fac").toFile();
        var wast2Json =
                Wast2Json.builder()
                        .withFile(new File("src/test/resources/fac.wast"))
                        .withOutput(outputFile)
                        .build();

        // Act
        wast2Json.process();

        // Assert
        assertTrue(outputFile.exists());
        assertTrue(outputFile.toPath().resolve("spec.0.wasm").toFile().exists());
    }

    @Test
    public void shouldRunWat2Wasm() throws Exception {
        var result = Wat2Wasm.parse(new File("../wasm-corpus/src/main/resources/wat/iterfact.wat"));

        assertTrue(result.length > 0);
        assertTrue(new String(result, UTF_8).contains("iterFact"));
    }

    @Test
    public void shouldRunWat2WasmOnString() {
        var moduleInstance =
                Instance.builder(
                                Parser.parse(
                                        Wat2Wasm.parse(
                                                "(module (func (export \"add\") (param $x"
                                                        + " i32) (param $y i32) (result i32)"
                                                        + " (i32.add (local.get $x) (local.get"
                                                        + " $y))))")))
                        .withInitialize(true)
                        .build();

        var addFunction = moduleInstance.export("add");
        var results = addFunction.apply(1, 41);
        assertEquals(42L, results[0]);
    }

    @Test
    public void shouldThrowMalformedException() throws Exception {
        var exitException =
                assertThrows(
                        WatParseException.class,
                        () ->
                                Wat2Wasm.parse(
                                        new File(
                                                "src/test/resources/utf8-invalid-encoding-spec.0.wat")));

        assertEquals(1, ((WasiExitException) exitException.getCause()).exitCode());
        assertTrue(
                exitException.getMessage().contains("malformed UTF-8 encoding"),
                "found: " + exitException.getMessage() + " doesn't contains the expected result");
    }

    @Test
    public void shouldValidateWatBeforeParsing() {
        var exitException =
                assertThrows(
                        WatParseException.class,
                        () -> Wat2Wasm.parse(new File("src/test/resources/address.1.wat")));

        assertEquals(1, ((WasiExitException) exitException.getCause()).exitCode());
        assertTrue(
                exitException.getMessage().contains("failed to validate"),
                "found: " + exitException.getMessage() + " doesn't contains the expected result");
    }

    @Test
    public void shouldValidateWat() {
        var exitException =
                assertThrows(
                        WatParseException.class,
                        () -> Validate.validate(new File("src/test/resources/address.1.wat")));

        assertEquals(1, ((WasiExitException) exitException.getCause()).exitCode());
        assertTrue(
                exitException.getMessage().contains("failed to validate"),
                "found: " + exitException.getMessage() + " doesn't contains the expected result");
    }

    @Test
    public void shouldValidateSimpleModuleWithWasm1() {
        Validate.builder()
                .withFeatures(WasmFeature.WASM1)
                .build()
                .validateModule(
                        "(module (func (export \"add\")"
                                + " (param i32) (param i32) (result i32)"
                                + " (i32.add (local.get 0) (local.get 1))))");
    }

    @Test
    public void shouldRejectSimdModuleWithWasm1() {
        var validator = Validate.builder().withFeatures(WasmFeature.WASM1).build();
        assertThrows(
                WatParseException.class,
                () ->
                        validator.validateModule(
                                "(module (func (result v128) (v128.const i32x4 0 0 0 0)))"));
    }

    @Test
    public void shouldAcceptSimdModuleWithWasm2() {
        Validate.builder()
                .withFeatures(WasmFeature.WASM2)
                .build()
                .validateModule("(module (func (result v128) (v128.const i32x4 0 0 0 0)))");
    }

    @Test
    public void shouldRejectSimdModuleWhenDisabled() {
        var validator =
                Validate.builder()
                        .withFeatures(WasmFeature.WASM2)
                        .withoutFeature(WasmFeature.SIMD)
                        .build();
        assertThrows(
                WatParseException.class,
                () ->
                        validator.validateModule(
                                "(module (func (result v128) (v128.const i32x4 0 0 0 0)))"));
    }
}

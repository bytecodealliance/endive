package run.endive.redline.experimental.build;

import static com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType;
import static com.github.javaparser.StaticJavaParser.parseType;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import run.endive.build.time.compiler.Config;
import run.endive.redline.experimental.api.NativeCodeSerializer;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.wasm.Parser;

public final class RedlineGenerator {

    private final Config config;

    public static List<String> allTargets() {
        return Arrays.stream(RedlineTarget.values())
                .map(RedlineTarget::triple)
                .collect(Collectors.toList());
    }

    public RedlineGenerator(Config config) {
        this.config = config;
    }

    public void generateNativeCode() throws IOException {
        if (!config.hasRedlineTargets()) {
            return;
        }
        var module = Parser.parse(config.wasmFile());
        var packagePath = config.getPackageName().replace('.', '/');
        var baseName = config.getBaseName();
        var resourceDir = config.targetClassFolder().resolve(packagePath);
        Files.createDirectories(resourceDir);

        for (String triple : config.redlineTargets()) {
            var target =
                    RedlineTarget.fromTriple(triple)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown target triple: " + triple));
            byte[][] compiledCode = NativeCompiler.compileAll(triple, module);
            var nativeFile =
                    resourceDir.resolve(baseName + "." + target.resourceSuffix() + ".native");

            try (var out = new FileOutputStream(nativeFile.toFile())) {
                NativeCodeSerializer.serialize(compiledCode, out);
            }
        }
    }

    public void extendGeneratedSources() throws IOException {
        if (!config.hasRedlineTargets()) {
            return;
        }
        var packagePath = config.getPackageName().replace('.', '/');
        var baseName = config.getBaseName();
        var sourceFile =
                config.targetSourceFolder().resolve(packagePath).resolve(baseName + ".java");

        var cu = StaticJavaParser.parse(sourceFile);
        var type = cu.getClassByName(baseName).orElseThrow();

        cu.addImport("run.endive.redline.experimental.api.NativeCodeSerializer");
        cu.addImport("run.endive.redline.experimental.api.NativeMachineFactoryProvider");
        cu.addImport("run.endive.redline.experimental.api.internal.RedlineTarget");
        cu.addImport("java.io.InputStream");
        cu.addImport("java.io.IOException");
        cu.addImport("java.io.UncheckedIOException");
        cu.addImport("run.endive.runtime.Instance");

        generateNativeCodeHolderInnerClass(type, baseName);
        generateLoadNativeCodeMethod(type);
        generateBuilderMethod(type, baseName);
        generateSafeBuilderMethod(type, baseName);

        Files.writeString(sourceFile, cu.toString());
    }

    private static void generateNativeCodeHolderInnerClass(
            ClassOrInterfaceDeclaration type, String moduleName) {
        var holderClass =
                new ClassOrInterfaceDeclaration(
                        NodeList.nodeList(
                                new Modifier(Modifier.Keyword.PRIVATE),
                                new Modifier(Modifier.Keyword.STATIC)),
                        false,
                        "NativeCodeHolder");
        type.addMember(holderClass);

        holderClass.addField(
                parseType("byte[][]"), "CODE", Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

        var initBody = holderClass.addStaticInitializer();

        initBody.addStatement(
                StaticJavaParser.parseStatement(
                        "var host = RedlineTarget.detectHost().orElse(null);"));

        initBody.addStatement(
                StaticJavaParser.parseStatement(
                        "if (host == null) {\n"
                                + "    CODE = null;\n"
                                + "} else {\n"
                                + "    String resource = \""
                                + moduleName
                                + ".\" + host.resourceSuffix() + \".native\";\n"
                                + "    try (InputStream in = "
                                + moduleName
                                + ".class.getResourceAsStream(resource)) {\n"
                                + "        CODE = (in == null) ? null"
                                + " : NativeCodeSerializer.deserialize(in);\n"
                                + "    } catch (IOException e) {\n"
                                + "        throw new UncheckedIOException("
                                + "\"Failed to load native code\", e);\n"
                                + "    }\n"
                                + "}"));
    }

    private static void generateLoadNativeCodeMethod(ClassOrInterfaceDeclaration type) {
        var method =
                type.addMethod("loadNativeCode", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseType("byte[][]"));
        method.createBody()
                .addStatement(
                        new ReturnStmt(
                                new FieldAccessExpr(new NameExpr("NativeCodeHolder"), "CODE")));
    }

    private static void generateBuilderMethod(ClassOrInterfaceDeclaration type, String moduleName) {
        var method =
                type.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("Instance.Builder"));

        var body = method.createBody();
        body.addStatement(StaticJavaParser.parseStatement("var module = load();"));
        body.addStatement(
                StaticJavaParser.parseStatement("byte[][] nativeCode = loadNativeCode();"));
        body.addStatement(
                StaticJavaParser.parseStatement(
                        "if (nativeCode != null) {\n"
                                + "    var provider = NativeMachineFactoryProvider.discover();\n"
                                + "    if (provider.isPresent()) {\n"
                                + "        return provider.get().builder(module, nativeCode);\n"
                                + "    }\n"
                                + "}"));
        body.addStatement(
                StaticJavaParser.parseStatement(
                        "return Instance.builder(module).withMachineFactory("
                                + moduleName
                                + "::create);"));
    }

    private static void generateSafeBuilderMethod(
            ClassOrInterfaceDeclaration type, String moduleName) {
        var method =
                type.addMethod("safeBuilder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("Instance.Builder"));

        method.createBody()
                .addStatement(
                        StaticJavaParser.parseStatement(
                                "return Instance.builder(load()).withMachineFactory("
                                        + moduleName
                                        + "::create);"));
    }
}

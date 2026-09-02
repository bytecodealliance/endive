package run.endive.redline.experimental.build;

import static com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType;
import static com.github.javaparser.StaticJavaParser.parseType;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.VarType;
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
        cu.addImport("java.util.Optional");
        cu.addImport("run.endive.runtime.Instance");

        generateNativeCodeHolderInnerClass(type, baseName);
        generateLoadNativeCodeMethod(type);
        generateNativeProviderMethod(type);
        generateBuilderMethod(type, baseName);
        generateSafeBuilderMethod(type, baseName);

        Files.writeString(sourceFile, cu.toString());
    }

    private static void generateNativeCodeHolderInnerClass(
            ClassOrInterfaceDeclaration type, String moduleName) {
        // Generates:
        // <code>
        //     private static class NativeCodeHolder {
        //         static final byte[][] CODE;
        //         static {
        //             byte[][] loaded = null;
        //             var host = RedlineTarget.detectHost().orElse(null);
        //             if (host != null) {
        //                 String resource = "<moduleName>." + host.resourceSuffix() + ".native";
        //                 try (InputStream in =
        //                         <moduleName>.class.getResourceAsStream(resource)) {
        //                     if (in != null) {
        //                         loaded = NativeCodeSerializer.deserialize(in);
        //                     }
        //                 } catch (IOException e) {
        //                     loaded = null;
        //                 }
        //             }
        //             CODE = loaded;
        //         }
        //     }
        // </code>
        //
        // Loading into a local keeps CODE definitely-assigned-once (a static final
        // cannot be assigned in both the try and the catch), and lets a missing or
        // unreadable blob leave CODE null so builder() falls back to the build-time
        // compiled bytecode instead of the class being permanently unusable.
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

        // byte[][] loaded = null;
        var loadedVar =
                new ExpressionStmt(
                        new VariableDeclarationExpr(
                                new VariableDeclarator(
                                        parseType("byte[][]"), "loaded", new NullLiteralExpr())));

        // var host = RedlineTarget.detectHost().orElse(null);
        var detectHost =
                new MethodCallExpr(
                        new MethodCallExpr(new NameExpr("RedlineTarget"), "detectHost"),
                        "orElse",
                        new NodeList<>(new NullLiteralExpr()));
        var hostVar =
                new ExpressionStmt(
                        new VariableDeclarationExpr(
                                new VariableDeclarator(new VarType(), "host", detectHost)));

        // String resource = "<moduleName>." + host.resourceSuffix() + ".native";
        var resourceName =
                new BinaryExpr(
                        new BinaryExpr(
                                new StringLiteralExpr(moduleName + "."),
                                new MethodCallExpr(new NameExpr("host"), "resourceSuffix"),
                                BinaryExpr.Operator.PLUS),
                        new StringLiteralExpr(".native"),
                        BinaryExpr.Operator.PLUS);
        var resourceVar =
                new ExpressionStmt(
                        new VariableDeclarationExpr(
                                new VariableDeclarator(
                                        parseClassOrInterfaceType("String"),
                                        "resource",
                                        resourceName)));

        // try (InputStream in = <moduleName>.class.getResourceAsStream(resource))
        var getResource =
                new MethodCallExpr(
                        new ClassExpr(parseType(moduleName)),
                        "getResourceAsStream",
                        new NodeList<>(new NameExpr("resource")));
        var streamResource =
                new VariableDeclarationExpr(
                        new VariableDeclarator(parseType("InputStream"), "in", getResource));

        // if (in != null) { loaded = NativeCodeSerializer.deserialize(in); }
        var assignLoaded =
                new ExpressionStmt(
                        new AssignExpr(
                                new NameExpr("loaded"),
                                new MethodCallExpr(
                                        new NameExpr("NativeCodeSerializer"),
                                        "deserialize",
                                        new NodeList<>(new NameExpr("in"))),
                                AssignExpr.Operator.ASSIGN));
        var ifStreamPresent =
                new IfStmt()
                        .setCondition(
                                new BinaryExpr(
                                        new NameExpr("in"),
                                        new NullLiteralExpr(),
                                        BinaryExpr.Operator.NOT_EQUALS))
                        .setThenStmt(new BlockStmt(new NodeList<>(assignLoaded)));

        // catch (IOException e) { loaded = null; }
        var resetLoaded =
                new ExpressionStmt(
                        new AssignExpr(
                                new NameExpr("loaded"),
                                new NullLiteralExpr(),
                                AssignExpr.Operator.ASSIGN));
        var catchIoException =
                new CatchClause()
                        .setParameter(new Parameter(parseClassOrInterfaceType("IOException"), "e"))
                        .setBody(new BlockStmt(new NodeList<>(resetLoaded)));

        var tryLoad =
                new TryStmt()
                        .setResources(new NodeList<>(streamResource))
                        .setTryBlock(new BlockStmt(new NodeList<>(ifStreamPresent)))
                        .setCatchClauses(new NodeList<>(catchIoException));

        var loadFromResource =
                new IfStmt()
                        .setCondition(
                                new BinaryExpr(
                                        new NameExpr("host"),
                                        new NullLiteralExpr(),
                                        BinaryExpr.Operator.NOT_EQUALS))
                        .setThenStmt(new BlockStmt(new NodeList<>(resourceVar, tryLoad)));

        // CODE = loaded;
        var assignCode =
                new ExpressionStmt(
                        new AssignExpr(
                                new NameExpr("CODE"),
                                new NameExpr("loaded"),
                                AssignExpr.Operator.ASSIGN));

        var initBody = holderClass.addStaticInitializer();
        initBody.addStatement(loadedVar);
        initBody.addStatement(hostVar);
        initBody.addStatement(loadFromResource);
        initBody.addStatement(assignCode);
    }

    private static void generateLoadNativeCodeMethod(ClassOrInterfaceDeclaration type) {
        // Generates:
        // <code>
        //     public static byte[][] loadNativeCode() {
        //         return NativeCodeHolder.CODE;
        //     }
        // </code>
        var method =
                type.addMethod("loadNativeCode", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseType("byte[][]"));
        method.createBody()
                .addStatement(
                        new ReturnStmt(
                                new FieldAccessExpr(new NameExpr("NativeCodeHolder"), "CODE")));
    }

    private static void generateNativeProviderMethod(ClassOrInterfaceDeclaration type) {
        // Generates:
        // <code>
        //     public static Optional&lt;NativeMachineFactoryProvider&gt; nativeProvider() {
        //         if (loadNativeCode() == null) {
        //             return Optional.empty();
        //         }
        //         return NativeMachineFactoryProvider.discover();
        //     }
        // </code>
        //
        // Non-empty exactly when builder() will take the native path, so callers can
        // tell which backend they got. Modules with imported memories or tables need
        // this: the native machines reject a memory that was not created by their own
        // factory, so imports must be built with the returned provider.
        var method =
                type.addMethod("nativeProvider", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(
                                parseClassOrInterfaceType(
                                        "Optional<NativeMachineFactoryProvider>"));

        var returnEmpty = new ReturnStmt(new MethodCallExpr(new NameExpr("Optional"), "empty"));
        var ifNoNativeCode =
                new IfStmt()
                        .setCondition(
                                new BinaryExpr(
                                        new MethodCallExpr("loadNativeCode"),
                                        new NullLiteralExpr(),
                                        BinaryExpr.Operator.EQUALS))
                        .setThenStmt(new BlockStmt(new NodeList<>(returnEmpty)));

        var body = method.createBody();
        body.addStatement(ifNoNativeCode);
        body.addStatement(
                new ReturnStmt(
                        new MethodCallExpr(
                                new NameExpr("NativeMachineFactoryProvider"), "discover")));
    }

    private static void generateBuilderMethod(ClassOrInterfaceDeclaration type, String moduleName) {
        // Generates:
        // <code>
        //     public static Instance.Builder builder() {
        //         var module = load();
        //         var provider = nativeProvider();
        //         if (provider.isPresent()) {
        //             return provider.get().builder(module, loadNativeCode());
        //         }
        //         return Instance.builder(module).withMachineFactory(<moduleName>::create);
        //     }
        // </code>
        //
        // The native path is selected through nativeProvider() so that callers
        // checking it see exactly the decision this method makes. Falling back means
        // the build-time compiled bytecode, not the interpreter.
        var method =
                type.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("Instance.Builder"));

        // var module = load();
        var moduleVar =
                new ExpressionStmt(
                        new VariableDeclarationExpr(
                                new VariableDeclarator(
                                        new VarType(), "module", new MethodCallExpr("load"))));

        // var provider = nativeProvider();
        var providerVar =
                new ExpressionStmt(
                        new VariableDeclarationExpr(
                                new VariableDeclarator(
                                        new VarType(),
                                        "provider",
                                        new MethodCallExpr("nativeProvider"))));

        // return provider.get().builder(module, loadNativeCode());
        var returnNative =
                new ReturnStmt(
                        new MethodCallExpr(
                                new MethodCallExpr(new NameExpr("provider"), "get"),
                                "builder",
                                new NodeList<>(
                                        new NameExpr("module"),
                                        new MethodCallExpr("loadNativeCode"))));

        var ifProviderPresent =
                new IfStmt()
                        .setCondition(new MethodCallExpr(new NameExpr("provider"), "isPresent"))
                        .setThenStmt(new BlockStmt(new NodeList<>(returnNative)));

        var body = method.createBody();
        body.addStatement(moduleVar);
        body.addStatement(providerVar);
        body.addStatement(ifProviderPresent);
        body.addStatement(new ReturnStmt(compiledBuilder(new NameExpr("module"), moduleName)));
    }

    private static void generateSafeBuilderMethod(
            ClassOrInterfaceDeclaration type, String moduleName) {
        // Generates:
        // <code>
        //     public static Instance.Builder safeBuilder() {
        //         return Instance.builder(load()).withMachineFactory(<moduleName>::create);
        //     }
        // </code>
        var method =
                type.addMethod("safeBuilder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("Instance.Builder"));

        method.createBody()
                .addStatement(
                        new ReturnStmt(compiledBuilder(new MethodCallExpr("load"), moduleName)));
    }

    /**
     * {@code Instance.builder(<module>).withMachineFactory(<moduleName>::create)}
     *
     * <p>{@code create} returns the machine the build-time compiler emitted as JVM
     * bytecode, so this is the compiled path, not the interpreter. The interpreter is
     * only reached per-function, for functions too large to fit a JVM method.
     */
    private static MethodCallExpr compiledBuilder(
            com.github.javaparser.ast.expr.Expression module, String moduleName) {
        return new MethodCallExpr(
                new MethodCallExpr(new NameExpr("Instance"), "builder", new NodeList<>(module)),
                "withMachineFactory",
                new NodeList<>(
                        new MethodReferenceExpr()
                                .setScope(new NameExpr(moduleName))
                                .setIdentifier("create")));
    }
}

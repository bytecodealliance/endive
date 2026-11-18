import org.scalajs.linker.interface.ModuleKind

enablePlugins(ScalaJSPlugin)

name := "hello-world"
scalaVersion := "2.13.18"
scalaJSUseMainModuleInitializer := true
scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.WasmModule))

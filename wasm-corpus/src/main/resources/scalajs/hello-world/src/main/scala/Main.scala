import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wasm
import scala.scalajs.wasm.annotation.WasmImport

object Main {
  // System.out is not available in a bare Wasm module
  @WasmImport("env", "print")
  def print(bytes: Array[Byte]): Unit = wasm.native

  def main(args: Array[String]): Unit =
    print("Hello from Scala.js!".getBytes(UTF_8))
}

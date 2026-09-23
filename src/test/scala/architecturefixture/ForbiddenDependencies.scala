// Deliberately invalid Scala bytecode, imported only by the checker's negative test.
package architecturefixture:
  package syntax:
    final class Node

  package model:
    final class BadSyntax(val node: syntax.Node)
    final class BadIo:
      def exists(path: java.nio.file.Path): Boolean = java.nio.file.Files.exists(path)

  package workspace:
    final class BadPlatform(val range: com.intellij.openapi.util.TextRange)

  package features:
    package rename:
      final class RenameHandler
    package completion:
      final class BadRename(val handler: rename.RenameHandler)

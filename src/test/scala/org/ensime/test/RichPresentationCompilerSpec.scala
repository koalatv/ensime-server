package org.ensime.test

import akka.event.slf4j.SLF4JLogging
import concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.internal.util.OffsetPosition
import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.slf4j.LoggerFactory

import org.ensime.indexer.SearchService
import org.ensime.indexer.SourceResolver
import org.ensime.model.OffsetSourcePosition
import org.ensime.server.RichPresentationCompiler
import org.ensime.test.util.Helpers

import pimpathon.file._

class RichPresentationCompilerSpec extends FunSpec with Matchers with SLF4JLogging {

  describe("RichPresentationCompiler") {

    it("should round-trip between typeFullName and askTypeInfoByName") {
      Helpers.withPresCompiler { (dir, cc) =>
        val file = Helpers.srcFile(dir, "abc.scala", Helpers.contents(
          "package com.example",
          "object /*1*/A { ",
          "   val /*1.1*/x: Int = 1",
          "   class  /*1.2*/X {} ",
          "   object /*1.3*/X {} ",
          "}",
          "class  /*2*/A { ",
          "   class  /*2.1*/X {} ",
          "   object /*2.2*/X {} ",
          "}"
        ))
        cc.askReloadFile(file)
        cc.askLoadedTyped(file)

        def roundtrip(label: String, expectedFullName: String) = {
          val comment = "/*" + label + "*/"
          val index = file.content.mkString.indexOf(comment)
          val tpe = cc.askTypeInfoAt(new OffsetPosition(file, index + comment.length)).get
          val fullName = tpe.fullName
          assert(fullName === expectedFullName)

          val tpe2 = cc.askTypeInfoByName(fullName).get
          assert(tpe2.fullName === expectedFullName)
        }

        roundtrip("1", "com.example.A$")
        roundtrip("1.1", "scala.Int")
        roundtrip("1.2", "com.example.A$$X")
        roundtrip("1.3", "com.example.A$$X$")
        roundtrip("2", "com.example.A")
        roundtrip("2.1", "com.example.A$X")
        roundtrip("2.2", "com.example.A$X$")
      }
    }

    it("should handle askMemberInfoByName") {
      Helpers.withPresCompiler { (dir, cc) =>
        val file = Helpers.srcFile(dir, "abc.scala", Helpers.contents(
          "package com.example",
          "object A { ",
          "   val x: Int = 1",
          "   class  X {}",
          "   object X {}",
          "}",
          "class A { ",
          "   class  X {}",
          "   object X {}",
          "}"
        ))
        cc.askReloadFile(file)
        cc.askLoadedTyped(file)

        def test(typeName: String, memberName: String, isType: Boolean, expectedTypeName: String, expectedDeclAs: Symbol) = {
          val sym = cc.askMemberInfoByName(typeName, memberName, isType).get
          assert(sym.localName === memberName)
          assert(sym.tpe.fullName === expectedTypeName)
          assert(sym.tpe.declaredAs === expectedDeclAs)
        }

        test("com.example$", "A", false, "com.example.A$", 'object)
        test("com.example.A$", "x", false, "scala.Int", 'class)
        test("com.example.A$", "X", false, "com.example.A$$X$", 'object)
        test("com.example.A$", "X", true, "com.example.A$$X", 'class)

        test("com.example$", "A", true, "com.example.A", 'class)
        test("com.example.A", "X", false, "com.example.A$X$", 'object)
        test("com.example.A", "X", true, "com.example.A$X", 'class)
      }
    }

    it("can get completions on member with no prefix") {
      Helpers.withPresCompiler { (dir, cc) =>
        val file = Helpers.srcFile(dir, "def.scala", Helpers.contents(
          "package com.example",
          "object A { def aMethod(a: Int) = a }",
          "object B { val x = A. "
        ))
        cc.askReloadFile(file)
        cc.askLoadedTyped(file)
        val p = new OffsetPosition(file, 78)
        val infoList = cc.completionsAt(p, 10, false)
        assert(infoList.completions.length > 1)
        assert(infoList.completions.head.name == "aMethod")
      }
    }

    it("can get completions on a member with a prefix") {
      Helpers.withPresCompiler { (dir, cc) =>
        val file = Helpers.srcFile(dir, "abc.scala", Helpers.contents(
          "package com.example",
          "object A { def aMethod(a: Int) = a }",
          "object B { val x = A.aMeth }"
        ))
        cc.askReloadFile(file)
        cc.askLoadedTyped(file)
        val p = new OffsetPosition(file, 83)
        val infoList = cc.completionsAt(p, 10, false)
        assert(infoList.completions.length == 1)
        assert(infoList.completions.head.name == "aMethod")
      }
    }

    it("can get completions on an object name") {
      Helpers.withPresCompiler { (dir, cc) =>
        val file = Helpers.srcFile(dir, "abc.scala", Helpers.contents(
          "package com.example",
          "object Abc { def aMethod(a: Int) = a }",
          "object B { val x = Ab }"
        ))
        cc.askReloadFile(file)
        cc.askLoadedTyped(file)
        val p = new OffsetPosition(file, 80)
        val infoList = cc.completionsAt(p, 10, false)
        assert(infoList.completions.length > 1)
        assert(infoList.completions.head.name == "Abc")
      }
    }

    it("should show classes without visible members in the inspector") {
      Helpers.withPresCompiler { (dir, cc) =>
        val file = Helpers.srcFile(dir, "abc.scala", Helpers.contents(
          "package com.example",
          "trait bidon { }",
          "case class pipo extends bidon { }"
        ))
        cc.askReloadFile(file)
        cc.askLoadedTyped(file)
        val info = cc.askInspectTypeAt(new OffsetPosition(file, 37))
        val supers = info.map(_.supers).getOrElse(List())
        val supersNames = supers.map(_.tpe.name).toList
        assert(supersNames.toSet === Set("pipo", "bidon", "Object", "Product", "Serializable", "Any"))
      }
    }

    it("should get symbol positions for compiled files") {
      Helpers.withPresCompiler { (dir, cc) =>
        val defsFile = Helpers.srcFile(dir, "com/example/defs.scala", Helpers.contents(
          "package com.example",
          "object /*1*/A { ",
          "   val /*1.1*/x: Int = 1",
          "   class /*1.2*/X {} ",
          "}",
          "class  /*2*/B { ",
          "   val /*2.1*/y: Int = 1",
          "   def /*2.2*/meth(a: String): Int = 1",
          "   def /*2.3*/meth(a: Int): Int = 1",
          "}",
          "trait  /*3*/C { ",
          "   val /*3.1*/z: Int = 1",
          "   class /*3.2*/Z {} ",
          "}",
          "class /*4*/D extends C { }"
        ), write = true)
        val usesFile = Helpers.srcFile(dir, "com/example/uses.scala", Helpers.contents(
          "package com.example",
          "object Test { ",
          "   val x_1 = A/*1*/",
          "   val x_1_1 = A.x/*1.1*/",
          "   val x_1_2 = new A.X/*1.2*/",
          "   val x_2 = new B/*2*/",
          "   val x_2_1 = new B().y/*2.1*/",
          "   val x_2_2 = new B().meth/*2.2*/(\"x\")",
          "   val x_2_3 = new B().meth/*2.3*/(1)",
          "   val x_3: C/*3*/ = new D/*4*/",
          "   val x_3_1 = x_3.z/*3.1*/",
          "   val x_3_2 = new x_3.Z/*3.2*/",
          "}"
        ))

        def test(label: String, cc: RichPresentationCompiler) = {
          val comment = "/*" + label + "*/"
          val defPos = defsFile.content.mkString.indexOf(comment) + comment.length;
          val usePos = usesFile.content.mkString.indexOf(comment) - 1;

          // Create a fresh pres. compiler unaffected by previous tests

          val cc1 = new RichPresentationCompiler(cc.config, cc.settings, cc.reporter, cc.parent, cc.indexer, cc.search)

          try {
            cc1.askReloadFile(usesFile)
            cc1.askLoadedTyped(usesFile)

            val info = cc1.askSymbolInfoAt(new OffsetPosition(usesFile, usePos)) match {
              case Some(x) => x
              case None => fail(s"For $comment, askSymbolInfoAt returned None")
            }
            val declPos = info.declPos
            declPos match {
              case Some(op: OffsetSourcePosition) => assert(op.offset === defPos)
              case _ => fail(s"For $comment, unexpected declPos value: $declPos")
            }
          } finally {
            cc1.askShutdown()
          }
        }

        Helpers.compileScala(
          List(defsFile.path),
          (dir / "target" / "classes").getPath,
          cc.settings.classpath.value
        )

        cc.search.refreshResolver()
        Await.result(cc.search.refresh(), 180.seconds)

        List("1", "1.1", "1.2", "2", "2.1", "2.2", "2.3", "3", "3.1", "3.2", "4").
          foreach(test(_, cc))
      }
    }
  }
}

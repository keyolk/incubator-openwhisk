/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity.test

import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.entity.ExecManifest
import whisk.core.entity.ExecManifest._
import whisk.core.entity.size._
import whisk.core.entity.ByteSize

import scala.util.Success

@RunWith(classOf[JUnitRunner])
class ExecManifestTests extends FlatSpec with WskActorSystem with StreamLogging with Matchers {

  behavior of "ExecManifest"

  private def manifestFactory(runtimes: JsObject) = {
    JsObject("runtimes" -> runtimes)
  }

  it should "parse an image name" in {
    Map(
      "i" -> ImageName("i"),
      "i:t" -> ImageName("i", tag = Some("t")),
      "i:tt" -> ImageName("i", tag = Some("tt")),
      "ii" -> ImageName("ii"),
      "ii:t" -> ImageName("ii", tag = Some("t")),
      "ii:tt" -> ImageName("ii", tag = Some("tt")),
      "p/i" -> ImageName("i", Some("p")),
      "pre/img" -> ImageName("img", Some("pre")),
      "pre/img:t" -> ImageName("img", Some("pre"), Some("t")),
      "pre1/pre2/img:t" -> ImageName("img", Some("pre1/pre2"), Some("t")),
      "pre1/pre2/img" -> ImageName("img", Some("pre1/pre2")))
      .foreach {
        case (s, v) => ImageName.fromString(s) shouldBe Success(v)
      }

    Seq("ABC", "x:8080/abc", "p/a:x:y").foreach { s =>
      a[DeserializationException] should be thrownBy ImageName.fromString(s).get
    }
  }

  it should "read a valid configuration without default prefix, default tag or blackbox images" in {
    val k1 = RuntimeManifest("k1", ImageName("???"))
    val k2 = RuntimeManifest("k2", ImageName("???"), default = Some(true))
    val p1 = RuntimeManifest("p1", ImageName("???"))
    val s1 = RuntimeManifest("s1", ImageName("???"), stemCells = Some(List(StemCell(2, 256.MB))))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson, "p1" -> Set(p1).toJson, "s1" -> Set(s1).toJson))
    val runtimes = ExecManifest.runtimes(mf, RuntimeManifestConfig()).get

    Seq("k1", "k2", "p1", "s1").foreach {
      runtimes.knownContainerRuntimes.contains(_) shouldBe true
    }

    runtimes.knownContainerRuntimes.contains("k3") shouldBe false

    runtimes.resolveDefaultRuntime("k1") shouldBe Some(k1)
    runtimes.resolveDefaultRuntime("k2") shouldBe Some(k2)
    runtimes.resolveDefaultRuntime("p1") shouldBe Some(p1)
    runtimes.resolveDefaultRuntime("s1") shouldBe Some(s1)

    runtimes.resolveDefaultRuntime("ks:default") shouldBe Some(k2)
    runtimes.resolveDefaultRuntime("p1:default") shouldBe Some(p1)
    runtimes.resolveDefaultRuntime("s1:default") shouldBe Some(s1)
  }

  it should "read a valid configuration without default prefix, default tag" in {
    val i1 = RuntimeManifest("i1", ImageName("???"))
    val i2 = RuntimeManifest("i2", ImageName("???", Some("ppp")), default = Some(true))
    val j1 = RuntimeManifest("j1", ImageName("???", Some("ppp"), Some("ttt")))
    val k1 = RuntimeManifest("k1", ImageName("???", None, Some("ttt")))
    val s1 = RuntimeManifest("s1", ImageName("???"), stemCells = Some(List(StemCell(2, 256.MB))))

    val mf =
      JsObject(
        "runtimes" -> JsObject(
          "is" -> Set(i1, i2).toJson,
          "js" -> Set(j1).toJson,
          "ks" -> Set(k1).toJson,
          "ss" -> Set(s1).toJson))
    val rmc = RuntimeManifestConfig(defaultImagePrefix = Some("pre"), defaultImageTag = Some("test"))
    val runtimes = ExecManifest.runtimes(mf, rmc).get

    runtimes.resolveDefaultRuntime("i1").get.image.publicImageName shouldBe "pre/???:test"
    runtimes.resolveDefaultRuntime("i2").get.image.publicImageName shouldBe "ppp/???:test"
    runtimes.resolveDefaultRuntime("j1").get.image.publicImageName shouldBe "ppp/???:ttt"
    runtimes.resolveDefaultRuntime("k1").get.image.publicImageName shouldBe "pre/???:ttt"
    runtimes.resolveDefaultRuntime("s1").get.image.publicImageName shouldBe "pre/???:test"
    runtimes.resolveDefaultRuntime("s1").get.stemCells.get(0).count shouldBe 2
    runtimes.resolveDefaultRuntime("s1").get.stemCells.get(0).memory shouldBe 256.MB
  }

  it should "read a valid configuration with blackbox images but without default prefix or tag" in {
    val imgs = Set(
      ImageName("???"),
      ImageName("???", Some("ppp")),
      ImageName("???", Some("ppp"), Some("ttt")),
      ImageName("???", None, Some("ttt")))

    val mf = JsObject("runtimes" -> JsObject(), "blackboxes" -> imgs.toJson)
    val runtimes = ExecManifest.runtimes(mf, RuntimeManifestConfig()).get

    runtimes.blackboxImages shouldBe imgs
    imgs.foreach(img => runtimes.skipDockerPull(img) shouldBe true)
    runtimes.skipDockerPull(ImageName("???", Some("bbb"))) shouldBe false
  }

  it should "read a valid configuration with blackbox images, default prefix and tag" in {
    val imgs = Set(
      ImageName("???"),
      ImageName("???", Some("ppp")),
      ImageName("???", Some("ppp"), Some("ttt")),
      ImageName("???", None, Some("ttt")))

    val mf = JsObject("runtimes" -> JsObject(), "blackboxes" -> imgs.toJson)
    val rmc = RuntimeManifestConfig(defaultImagePrefix = Some("pre"), defaultImageTag = Some("test"))
    val runtimes = ExecManifest.runtimes(mf, rmc).get

    runtimes.blackboxImages shouldBe {
      Set(
        ImageName("???", Some("pre"), Some("test")),
        ImageName("???", Some("ppp"), Some("test")),
        ImageName("???", Some("ppp"), Some("ttt")),
        ImageName("???", Some("pre"), Some("ttt")))
    }

    runtimes.skipDockerPull(ImageName("???", Some("pre"), Some("test"))) shouldBe true
    runtimes.skipDockerPull(ImageName("???", Some("bbb"), Some("test"))) shouldBe false
  }

  it should "reject runtimes with multiple defaults" in {
    val k1 = RuntimeManifest("k1", ImageName("???"), default = Some(true))
    val k2 = RuntimeManifest("k2", ImageName("???"), default = Some(true))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson))

    an[IllegalArgumentException] should be thrownBy ExecManifest.runtimes(mf, RuntimeManifestConfig()).get
  }

  it should "reject finding a default when none specified for multiple versions in the same family" in {
    val k1 = RuntimeManifest("k1", ImageName("???"))
    val k2 = RuntimeManifest("k2", ImageName("???"))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson))

    an[IllegalArgumentException] should be thrownBy ExecManifest.runtimes(mf, RuntimeManifestConfig()).get
  }

  it should "prefix image name with overrides" in {
    val name = "xyz"
    ExecManifest.ImageName(name, Some(""), Some("")).publicImageName shouldBe name

    Seq(
      (ExecManifest.ImageName(name), name),
      (ExecManifest.ImageName(name, Some("pre")), s"pre/$name"),
      (ExecManifest.ImageName(name, None, Some("t")), s"$name:t"),
      (ExecManifest.ImageName(name, Some("pre"), Some("t")), s"pre/$name:t")).foreach {
      case (image, exp) =>
        image.publicImageName shouldBe exp

        image.localImageName("", "", None) shouldBe image.tag.map(t => s"$name:$t").getOrElse(s"$name:latest")
        image.localImageName("", "p", None) shouldBe image.tag.map(t => s"p/$name:$t").getOrElse(s"p/$name:latest")
        image.localImageName("r", "", None) shouldBe image.tag.map(t => s"r/$name:$t").getOrElse(s"r/$name:latest")
        image.localImageName("r", "p", None) shouldBe image.tag.map(t => s"r/p/$name:$t").getOrElse(s"r/p/$name:latest")
        image.localImageName("r", "p", Some("tag")) shouldBe s"r/p/$name:tag"
    }
  }

  it should "indicate image is local if it matches deployment docker prefix" in {
    val mf = JsObject()
    val rmc = RuntimeManifestConfig(bypassPullForLocalImages = Some(true), localImagePrefix = Some("localpre"))
    val manifest = ExecManifest.runtimes(mf, rmc)

    manifest.get.skipDockerPull(ImageName(prefix = Some("x"), name = "y")) shouldBe false
    manifest.get.skipDockerPull(ImageName(prefix = Some("localpre"), name = "y")) shouldBe true
  }

  it should "de/serialize stem cell configuration" in {
    val cell = StemCell(3, 128.MB)
    val cellAsJson = JsObject("count" -> JsNumber(3), "memory" -> JsString("128 MB"))
    stemCellSerdes.write(cell) shouldBe cellAsJson
    stemCellSerdes.read(cellAsJson) shouldBe cell

    an[IllegalArgumentException] shouldBe thrownBy {
      StemCell(-1, 128.MB)
    }

    an[IllegalArgumentException] shouldBe thrownBy {
      StemCell(0, 128.MB)
    }

    an[IllegalArgumentException] shouldBe thrownBy {
      val cellAsJson = JsObject("count" -> JsNumber(0), "memory" -> JsString("128 MB"))
      stemCellSerdes.read(cellAsJson)
    }

    the[IllegalArgumentException] thrownBy {
      val cellAsJson = JsObject("count" -> JsNumber(1), "memory" -> JsString("128"))
      stemCellSerdes.read(cellAsJson)
    } should have message {
      ByteSize.formatError
    }
  }

  it should "parse manifest from JSON string" in {
    val json = """
                 |{ "runtimes": {
                 |    "nodef": [
                 |      {
                 |        "kind": "nodejs:6",
                 |        "image": {
                 |          "name": "nodejsaction"
                 |        },
                 |        "stemCells": [{
                 |          "count": 1,
                 |          "memory": "128 MB"
                 |        }]
                 |      }, {
                 |        "kind": "nodejs:8",
                 |        "default": true,
                 |        "image": {
                 |          "name": "nodejsaction"
                 |        },
                 |        "stemCells": [{
                 |          "count": 1,
                 |          "memory": "128 MB"
                 |        }, {
                 |          "count": 1,
                 |          "memory": "256 MB"
                 |        }]
                 |      }
                 |    ],
                 |    "pythonf": [{
                 |      "kind": "python",
                 |      "image": {
                 |        "name": "pythonaction"
                 |      },
                 |      "stemCells": [{
                 |        "count": 2,
                 |        "memory": "256 MB"
                 |      }]
                 |    }],
                 |    "swiftf": [{
                 |      "kind": "swift",
                 |      "image": {
                 |        "name": "swiftaction"
                 |      },
                 |      "stemCells": []
                 |    }],
                 |    "phpf": [{
                 |      "kind": "php",
                 |      "image": {
                 |        "name": "phpaction"
                 |      }
                 |    }]
                 |  }
                 |}
                 |""".stripMargin.parseJson.asJsObject

    val js6 = RuntimeManifest("nodejs:6", ImageName("nodejsaction"), stemCells = Some(List(StemCell(1, 128.MB))))
    val js8 = RuntimeManifest(
      "nodejs:8",
      ImageName("nodejsaction"),
      default = Some(true),
      stemCells = Some(List(StemCell(1, 128.MB), StemCell(1, 256.MB))))
    val py = RuntimeManifest("python", ImageName("pythonaction"), stemCells = Some(List(StemCell(2, 256.MB))))
    val sw = RuntimeManifest("swift", ImageName("swiftaction"), stemCells = Some(List.empty))
    val ph = RuntimeManifest("php", ImageName("phpaction"))
    val mf = ExecManifest.runtimes(json, RuntimeManifestConfig()).get

    mf shouldBe {
      Runtimes(
        Set(
          RuntimeFamily("nodef", Set(js6, js8)),
          RuntimeFamily("pythonf", Set(py)),
          RuntimeFamily("swiftf", Set(sw)),
          RuntimeFamily("phpf", Set(ph))),
        Set.empty,
        None)
    }

    def stemCellFactory(m: RuntimeManifest, cells: List[StemCell]) = cells.map { c =>
      (m.kind, m.image, c.count, c.memory)
    }

    mf.stemcells.flatMap {
      case (m, cells) =>
        cells.map { c =>
          (m.kind, m.image, c.count, c.memory)
        }
    }.toList should contain theSameElementsAs List(
      (js6.kind, js6.image, 1, 128.MB),
      (js8.kind, js8.image, 1, 128.MB),
      (js8.kind, js8.image, 1, 256.MB),
      (py.kind, py.image, 2, 256.MB))
  }
}

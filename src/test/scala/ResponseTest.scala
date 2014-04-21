package brando

import akka.util.ByteString
import org.scalatest.FunSpec

class ResponseTest extends FunSpec {
  describe("Utf8String") {
    it("should extract an utf8 String from a ByteString") {
      val ok = Response.AsString.unapply(Some(ByteString("ok")))
      assertResult(Some("ok"))(ok)
    }
  }

  describe("Strings") {
    it("should extract a list of string from a option bytestring list ") {
      val resp = Some(List(Some(ByteString("l1")), Some(ByteString("l2")), Some(ByteString("l3"))))
      val seq = Response.AsStrings.unapply(resp)
      assertResult(Some(Seq("l1", "l2", "l3")))(seq)
    }

    it("shouldn't extract if it is another type") {
      val seq = Response.AsStrings.unapply(Some(List(Some(12L))))
      assertResult(None)(seq)
    }
  }

  describe("Bytes Sequences") {
    it("should extract a list of string from a option bytestring list ") {
      val resp = Some(List(Some(ByteString(0, 1)), Some(ByteString(2, 3))))
      val seq = Response.AsByteSeqs.unapply(resp)
      assertResult(Some(Seq(Seq(0, 1), Seq(2, 3))))(seq)
    }

    it("shouldn't extract if it is another type") {
      val seq = Response.AsByteSeqs.unapply(Some(List(Some(12L))))
      assertResult(None)(seq)
    }
  }

  describe("Strings Hashes") {
    it("should extract a map when the result list has an heaven size") {
      val resp = Some(List(Some(ByteString("k1")), Some(ByteString("v1")), Some(ByteString("k2")), Some(ByteString("v2"))))
      val map = Response.AsStringsHash.unapply(resp)
      assertResult(Some(Map("k1" -> "v1", "k2" -> "v2")))(map)
    }

    it("should extract an empty map when the result list is empty") {
      val map = Response.AsStringsHash.unapply(Some(List.empty))
      assertResult(Some(Map.empty))(map)
    }

    it("should fails when the result list has an odd size") {
      val map = Response.AsStringsHash.unapply(Some(List(Some(ByteString("k1")))))
      assertResult(None)(map)
    }
  }

}

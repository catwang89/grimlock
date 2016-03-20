// Copyright 2014,2015,2016 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package au.com.cba.omnia.grimlock.framework.content

import au.com.cba.omnia.grimlock.framework._
import au.com.cba.omnia.grimlock.framework.encoding._
import au.com.cba.omnia.grimlock.framework.content.metadata._

import java.util.regex.Pattern

/** Contents of a cell in a matrix. */
trait Content {
  /** Type of the data. */
  type T

  /** Schema (description) of the value. */
  val schema: Schema { type S = T }

  /** The value of the variable. */
  val value: Value { type V = T }

  /** Check if the content is valid according to its schema. */
  def isValid() = schema.validate(value)

  override def toString(): String = "Content(" + schema.toString + "," + value.toString + ")"

  /**
   * Converts the content to a consise (terse) string.
   *
   * @return Short string representation.
   */
  def toShortString(): String = value.toShortString

  /**
   * Converts the content to a consise (terse) string.
   *
   * @param separator The separator to use between the fields.
   * @return Short string representation.
   */
  def toShortString(separator: String): String = {
    value.codec.toShortString + separator + schema.toShortString(value.codec) + separator + value.toShortString
  }
}

/** Companion object to `Content` trait. */
object Content {
  /** Type for parsing a string to `Content`. */
  type Parser = (String) => Option[Content]

  /**
   * Construct a content from a schema and value.
   *
   * @param schema Schema of the variable value.
   * @param value  Value of the variable.
   */
  def apply[V](schema: Schema { type S = V }, value: Valueable { type T = V }): Content = ContentImpl(schema, value())

  /** Standard `unapply` method for pattern matching. */
  def unapply(con: Content): Option[(Schema, Value)] = Some((con.schema, con.value))

  /**
   * Return content parser from codec and schema.
   *
   * @param codec  The codec to decode content with.
   * @param schema The schema to validate content with.
   *
   * @return A content parser.
   */
  def parse[T](codec: Codec { type C = T }, schema: Schema { type S = T }): Parser = {
    (str: String) => {
      codec
        .decode(str)
        .flatMap {
          case v if (schema.validate(v)) => Some(Content(schema, v))
          case _ => None
        }
    }
  }

  /**
   * Parse a content from string.
   *
   * @param str       The string to parse.
   * @param separator The separator between codec, schema and value.
   *
   * @return A `Some[Content]` if successful, `None` otherwise.
   */
  def fromShortString(str: String, separator: String = "|"): Option[Content] = {
    str.split(Pattern.quote(separator)) match {
      case Array(c, s, v) =>
        Codec.fromShortString(c)
          .flatMap {
            case codec =>
              Schema.fromShortString(s, codec).flatMap { case schema => Content.parse[codec.C](codec, schema)(v) }
          }
      case _ => None
    }
  }

  def toString(separator: String = "|", descriptive: Boolean = false): (Content) => TraversableOnce[String] = {
    (t: Content) => if (descriptive) { Some(t.toString) } else { Some(t.toShortString(separator)) }
  }
}

private case class ContentImpl[X](schema: Schema { type S = X }, value: Value { type V = X }) extends Content {
  type T = X
}

/** Base trait that represents the contents of a matrix. */
trait Contents extends Persist[Content] {
  /**
   * Persist to disk.
   *
   * @param file   Name of the output file.
   * @param writer Writer that converts `Content` to string.
   *
   * @return A `U[Content]` which is this object's data.
   */
  def saveAsText(file: String, writer: TextWriter = Content.toString())(implicit ctx: C): U[Content]
}

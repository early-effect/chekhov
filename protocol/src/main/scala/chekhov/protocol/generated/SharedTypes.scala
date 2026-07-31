package chekhov.protocol.generated

import zio.json.*

final case class Point(
    x: Double,
    y: Double,
) derives JsonCodec

final case class Rect(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
) derives JsonCodec

final case class NameValue(
    name: String,
    value: String,
) derives JsonCodec

final case class StackFrame(
    file: String,
    line: Double,
    column: Double,
    function: Option[String] = None,
) derives JsonCodec

final case class ViewportSize(
    width: Double,
    height: Double,
) derives JsonCodec

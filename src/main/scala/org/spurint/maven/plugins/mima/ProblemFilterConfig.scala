package org.spurint.maven.plugins.mima

class ProblemFilterConfig {
  private var name: String = _
  private var value: String = _

  def getName: String = name
  def getValue: String = value

  override def toString: String = s"ProblemFilterConfig[$name, $value]"
}

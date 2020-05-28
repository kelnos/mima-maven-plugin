package org.spurint.maven.plugins.mima

import com.typesafe.tools.mima.core.util.log.Logging
import com.typesafe.tools.mima.core.{Config, Problem, ProblemFilter, ProblemFilters}
import com.typesafe.tools.mima.lib.MiMaLib
import java.io.{File, FileNotFoundException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.util
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.{Artifact, DefaultArtifact}
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{AbstractMojo, AbstractMojoExecutionException, MojoExecutionException, MojoFailureException}
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.{DefaultProjectBuildingRequest, MavenProject}
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate
import org.apache.maven.shared.transfer.artifact.resolve.{ArtifactResolver, ArtifactResolverException}
import scala.collection.JavaConverters._
import scala.tools.nsc.classpath.{AggregateClassPath, ClassPathFactory}
import scala.tools.nsc.util.ClassPath
import scala.util.control.NonFatal
import scala.xml.XML

sealed trait Direction
object Direction {
  sealed trait Backward extends Direction { override val toString: String = "backward" }
  sealed trait Forward extends Direction { override val toString: String = "forward" }
  case object Backward extends Backward
  case object Forward extends Forward
  case object Both extends Backward with Forward { override val toString: String = "both" }

  def unapply(s: String): Option[Direction] = s match {
    case "backward" | "backwards" => Some(Backward)
    case "forward" | "forwards" => Some(Forward)
    case "both" => Some(Both)
    case _ => None
  }
}

sealed trait FailOnMode
object FailOnMode {
  case object SemVer extends FailOnMode
  case object Always extends FailOnMode
  case object Never extends FailOnMode

  def unapply(s: String): Option[FailOnMode] = s match {
    case "semver" => Some(SemVer)
    case "always" | "true" => Some(Always)
    case "never" | "false" => Some(Never)
    case _ => None
  }
}

object MiMaMojo {
  private val PACKAGING_TYPES = Set(
    "ejb",
    "jar",
    "war",
  )
}

@Mojo(name = "check-abi", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.TEST)
class MiMaMojo extends AbstractMojo {
  @Parameter(property = "previousArtifact", defaultValue = "latest-release")
  private var previousArtifact: String = _

  @Parameter(property = "failOnProblem", defaultValue = "semver")
  private var failOnProblem: String = "semver"

  @Parameter(property = "failOnNoPrevious", defaultValue = "false")
  private var failOnNoPrevious: Boolean = false

  @Parameter(property = "direction")
  private var direction: String = "backward"

  @Parameter(property = "filters")
  private var filters: util.List[ProblemFilterConfig] = _

  @Parameter(property = "mima.skip", defaultValue = "false")
  private var skip: Boolean = false

  @Parameter(property = "readTimeout", defaultValue = "4000")
  private var readTimeout: Int = 4000

  @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
  private var buildOutputDirectory: File = _

  @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
  private var remoteRepositories: util.List[ArtifactRepository] = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private var session: MavenSession = _

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private var project: MavenProject = _

  @Component
  private var artifactResolver: ArtifactResolver = _

  @Component
  private var artifactHandlerManager: ArtifactHandlerManager = _

  private object mavenLogging extends Logging {
    override def debugLog(str: String): Unit = getLog.debug(str)
    override def info(str: String): Unit = getLog.info(str)
    override def warn(str: String): Unit = getLog.warn(str)
    override def error(str: String): Unit = getLog.error(str)
  }

  private def reporterClassPath(classpath: String): ClassPath =
    AggregateClassPath.createAggregate(new ClassPathFactory(Config.settings).classesInPath(classpath): _*)

  override def execute(): Unit = {
    if (skip) {
      return
    } else if (!canCompatCheck(this.project)) {
      return
    }

    val direction = Direction.unapply(this.direction)
      .getOrElse(throw new MojoExecutionException(s"Unknown direction type '${this.direction}'"))

    val failOnProblem = FailOnMode.unapply(this.failOnProblem)
      .getOrElse(throw new MojoExecutionException(s"Unknown incompatibilitiesAllowedMode type '${this.failOnProblem}'"))

    val filters = this.filters.asScala.map(pt => ProblemFilters.exclude(pt.getName, pt.getValue)).toList

    (this.previousArtifact match {
      case "latest-release" => fetchLatestReleaseVersion()
      case v => Option(v)
    }).fold({
      if (this.failOnNoPrevious) {
        throw new MojoExecutionException("No previous artifact version found for binary compatilibity checks")
      } else {
        getLog.info("No previous artifact version found; not checking binary compatibility")
      }
    })({ previousArtifactVersion =>
      val prevArtifact = new DefaultArtifact(
        this.project.getGroupId, this.project.getArtifactId, previousArtifactVersion,
        null, this.project.getPackaging, null,
        this.artifactHandlerManager.getArtifactHandler(this.project.getPackaging))
      val prev = resolveArtifact(prevArtifact)

      val classpathStr = this.project.getArtifacts.asInstanceOf[util.Set[Artifact]].asScala
        .map(resolveArtifact)
        .map(_.getAbsolutePath)
        .mkString(File.pathSeparator)
      val classpath = reporterClassPath(classpathStr)

      val (bcProblems, fcProblems) = runMima(classpath, direction, prev)
      reportErrors(failOnProblem, prevArtifact.getVersion, filters, bcProblems, fcProblems)
    })
  }

  private def reportErrors(failOnProblem: FailOnMode, prevVersion: String, filters: List[ProblemFilter], bcProblems: List[Problem], fcProblems: List[Problem]): Unit = {
    def isReported(classification: String)(problem: Problem): Boolean = filters.forall({ filter =>
      if (filter(problem)) {
        true
      } else {
        getLog.debug(s"Filtered out: ${problem.description(classification)}")
        getLog.debug(s"    filtered by: $filter")
        false
      }
    })

    def howToFilter(p: Problem): Option[String] =
      p.matchName.map(mn => s"<filter><name>${p.getClass.getSimpleName}</name><value>$mn</value></filter>")

    def pretty(affected: String)(p: Problem): List[String] = {
      val desc = p.description(affected)
      val howToFilterMsg = howToFilter(p).map(s => s"    filter with: $s")
      List(s" * $desc") ++ howToFilterMsg
    }

    val shouldFailOnProblem = shouldFail(failOnProblem, prevVersion)

    val bcErrors = bcProblems.filter(isReported("current"))
    val fcErrors = fcProblems.filter(isReported("other"))

    val count = bcErrors.length + fcErrors.length
    val filteredCount = bcProblems.length + fcProblems.length - bcErrors.length - fcErrors.length
    val filteredMsg = if (filteredCount > 0) s" (filtered $filteredCount)" else ""
    getLog.info(s"Found $count potential binary incompatibilities while checking against $prevVersion$filteredMsg")

    (bcProblems.map(pretty("current")) ++ fcProblems.map(pretty("other"))).foreach({ p =>
      if (shouldFailOnProblem) p.foreach(getLog.error)
      else p.foreach(getLog.warn)
    })

    if (shouldFailOnProblem && (bcProblems.nonEmpty || fcProblems.nonEmpty)) {
      throw new MojoFailureException("Binary compatibility check failed (see above for errors)")
    }
  }

  private def runMima(classpath: ClassPath, direction: Direction, prev: File): (List[Problem], List[Problem]) = {
    val checkBC = collectProblems(classpath, prev, this.buildOutputDirectory)
    val checkFC = collectProblems(classpath, this.buildOutputDirectory, prev)

    direction match {
      case Direction.Backward => (checkBC(), Nil)
      case Direction.Forward => (Nil, checkFC())
      case Direction.Both => (checkBC(), checkFC())
    }
  }

  private def collectProblems(classpath: ClassPath, prev: File, cur: File): () => List[Problem] =
    () => new MiMaLib(classpath, mavenLogging).collectProblems(prev, cur)

  private def resolveArtifact(artifact: Artifact): File = {
    val buildingRequest = new DefaultProjectBuildingRequest(this.session.getProjectBuildingRequest)
    buildingRequest.setRemoteRepositories(remoteRepositories)

    val coordinate = new DefaultArtifactCoordinate
    coordinate.setGroupId(artifact.getGroupId)
    coordinate.setArtifactId(artifact.getArtifactId)
    coordinate.setVersion(artifact.getVersion)
    Option(artifact.getArtifactHandler).map(_.getExtension).foreach(coordinate.setExtension)
    coordinate.setClassifier(artifact.getClassifier)

    try {
      val result = this.artifactResolver.resolveArtifact(buildingRequest, coordinate)
      Option(result.getArtifact)
        .getOrElse(throw new ArtifactResolverException("Resolver returned null artifact", new NullPointerException))
        .getFile
    } catch {
      case e: ArtifactResolverException => throw new MojoExecutionException(e.getMessage, e)
    }
  }

  private def fetchLatestReleaseVersion(): Option[String] = {
    this.remoteRepositories.asScala.foldLeft(Option.empty[String])({
      case (None, repo) =>
        val url = new URL(s"${repo.getUrl}/${this.project.getGroupId.replaceAllLiterally(".", "/")}/${this.project.getArtifactId}/maven-metadata.xml")
        makeHttpRequest(url).flatMap({ input =>
          val xml = XML.load(input)
          (xml \ "versioning" \ "release").headOption.map(_.text.trim)
        })
      case (v @ Some(_), _) => v
    })
  }

  private def makeHttpRequest(url: URL, retriesLeft: Int = 3): Option[InputStream] = {
    try {
      val conn = url.openConnection() match {
        case huc: HttpURLConnection => huc
        case x => throw new AssertionError(s"${x.getClass.getName} should be a HttpURLConnection")
      }
      conn.setConnectTimeout(2000)
      conn.setReadTimeout(readTimeout)
      conn.getResponseCode match {
        case x if x >= 200 && x < 300 =>
          Option(conn.getInputStream)
        case x if x >= 300 && x < 400 && retriesLeft > 0 =>
          Option(conn.getHeaderField("Location")).fold(
            throw new MojoExecutionException(s"Repository server at $url returned status $x but with no Location header")
          )(
            location => makeHttpRequest(new URL(location), retriesLeft - 1)
          )
        case 404 | 410 =>
          None
        case x if x >= 400 && x < 500 && x != 408 =>
          throw new MojoExecutionException(s"Repository server at $url returned status $x")
        case _ if retriesLeft > 0 =>
          makeHttpRequest(url, retriesLeft - 1)
        case x =>
          throw new MojoExecutionException(s"Repository server at $url returned status $x")
      }
    } catch {
      case _: FileNotFoundException => None
      case NonFatal(e) if !classOf[AbstractMojoExecutionException].isAssignableFrom(e.getClass) =>
        throw new MojoExecutionException(e.getMessage, e)
    }
  }

  private def canCompatCheck(project: MavenProject): Boolean = MiMaMojo.PACKAGING_TYPES.contains(project.getPackaging)

  private def shouldFail(mode: FailOnMode, prevVersion: String): Boolean = {
    mode match {
      case FailOnMode.Always => true
      case FailOnMode.Never => false
      case FailOnMode.SemVer =>
        val curVersion = this.project.getVersion
        val isPrerelease = curVersion.startsWith("0.")
        prevVersion.split("\\.").zip(curVersion.split("\\."))
          .take(if (isPrerelease) 2 else 1)
          .forall({ case (prev, cur) => prev == cur })
    }
  }
}

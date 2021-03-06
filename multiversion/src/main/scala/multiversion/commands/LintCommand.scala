package multiversion.commands

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.JavaConverters._

import com.twitter.multiversion.Build.QueryResult
import moped.annotations.CommandName
import moped.annotations.Description
import moped.annotations.PositionalArguments
import moped.cli.Application
import moped.cli.Command
import moped.cli.CommandParser
import moped.json.Result
import multiversion.BazelUtil
import multiversion.diagnostics.MultidepsEnrichments._
import multiversion.indexes.DependenciesIndex
import multiversion.indexes.TargetIndex
import multiversion.outputs.LintOutput
import multiversion.resolvers.SimpleDependency
import org.typelevel.paiges.Doc

@CommandName("lint")
case class LintCommand(
    @Description("File to write lint report") lintReportPath: Option[Path] = None,
    @Description("Path to bazel executable") bazel: Path = Paths.get("bazel"),
    @PositionalArguments queryExpressions: List[String] = Nil,
    app: Application = Application.default
) extends Command {
  private def runQuery(queryExpression: String): Result[QueryResult] = {
    val command = List(
      "query",
      queryExpression,
      "--noimplicit_deps",
      "--notool_deps",
      "--output=proto"
    )
    BazelUtil.bazel(app, bazel, command).map { out =>
      QueryResult.parseFrom(out.bytes)
    }
  }

  def run(): Int = app.complete(runResult())

  def runResult(): Result[Unit] = {
    val expr = queryExpressions.mkString(" ")
    for {
      result <- runQuery(s"allpaths($expr, @maven//:all)")
      rootsResult <- runQuery(expr)
    } yield {
      val roots = rootsResult.getTargetList().asScala.map(_.getRule().getName())
      val index = new DependenciesIndex(result)
      val lintResults = roots.map { root =>
        val deps = index.dependencies(root)
        val errors = deps.groupBy(_.dependency.map(_.module)).collect {
          case (Some(dep), ts) if ts.flatMap(_.dependency.map(_.version).toSet).toSet.size > 1 =>
            dep -> ts.collect {
              case TargetIndex(_, _, _, Some(dep)) => dep
            }
        }
        val isTransitive = errors.toList.flatMap {
          case (m, vs) =>
            for {
              v <- vs
              dep = SimpleDependency(m, v.version, v.classifier)
              tdep <- index.dependencies(dep)
              if tdep.dependency != Some(dep)
            } yield tdep
        }.toSet

        val reportedErrors = errors.filter {
          case (module, deps0) =>
            val deps = deps0
              .map(v => SimpleDependency(module, v.version, v.classifier))
              .flatMap(index.byDependency.get(_))
            !deps.exists(isTransitive)
        }

        val isFailure = reportedErrors.nonEmpty && !isPending(app, root)
        LintOutput(root, reportedErrors, isFailure)
      }

      for {
        LintOutput(root, errors, isFailure) <- lintResults
        log =
          if (isFailure) app.reporter.error _
          else (s: String) => app.reporter.warning(s"(Pending) $s")
        (module, versions) <- errors
      } {
        log(
          s"target '$root' depends on conflicting versions of the 3rdparty dependency '${module.repr}:{${versions.map(_.version).commas}}'.\n" +
            s"\tTo fix this problem, modify the dependency list of this target so that it only depends on one version of the 3rdparty module '${module.repr}'"
        )
      }

      lintReportPath
        .map(p => if (p.isAbsolute()) p else app.env.workingDirectory.resolve(p))
        .foreach { out =>
          val docs = lintResults.filter(_.conflicts.nonEmpty).map(_.toDoc)
          val rendered = Doc.intercalate(Doc.line, docs).render(Int.MaxValue)
          Files.createDirectories(out.getParent())
          Files.write(out, rendered.getBytes(StandardCharsets.UTF_8))
        }
    }
  }

  private def isPending(app: Application, label: String): Boolean = {
    val command = List(
      "query",
      s"""attr("tags", "dupped_3rdparty", $label)"""
    )
    BazelUtil
      .bazel(app, bazel, command)
      .map { out =>
        out.trim.linesIterator.contains(label)
      }
      .getOrElse(false)
  }
}

object LintCommand {
  val default: LintCommand = LintCommand()
  implicit val parser: CommandParser[LintCommand] =
    CommandParser.derive(default)
}

package viper.gobra.frontend.info

import org.bitbucket.inkytonik.kiama.relation.Tree
import viper.gobra.ast.frontend.PNode.PPkg
import viper.gobra.ast.frontend.{PNode, PPackage}
import viper.gobra.frontend.Config
import viper.gobra.frontend.info.implementation.TypeInfoImpl
import viper.gobra.frontend.info.implementation.typing.ghost.separation.{GhostLessPrinter, GoifyingPrinter}
import viper.gobra.reporting.{CyclicImportError, TypeCheckDebugMessage, TypeCheckFailureMessage, TypeCheckSuccessMessage, TypeError, VerifierError}

import scala.collection.immutable.ListMap

import scala.concurrent.{Future, ExecutionContext}

object Info {

  implicit val executionContext = ExecutionContext.global

  type GoTree = Tree[PNode, PPackage]

  class Context {
    /** stores the results of all imported packages that have been parsed and type checked so far */
    private var contextMap: Map[PPkg, Either[Vector[VerifierError], ExternalTypeInfo]] = ListMap[PPkg, Either[Vector[VerifierError], ExternalTypeInfo]]()
    /** keeps track of the package dependencies that are currently resolved. This information is used to detect cycles */
    private var pendingPackages: Vector[PPkg] = Vector()
    /** stores all cycles that have been discovered so far */
    private var knownImportCycles: Set[Vector[PPkg]] = Set()

    def addPackage(typeInfo: ExternalTypeInfo): Unit = {
      pendingPackages = pendingPackages.filterNot(_ == typeInfo.pkgName.name)
      contextMap = contextMap + (typeInfo.pkgName.name -> Right(typeInfo))
    }

    def addErrenousPackage(pkg: PPkg, errors: Vector[VerifierError]): Unit = {
      pendingPackages = pendingPackages.filterNot(_ == pkg)
      contextMap = contextMap + (pkg -> Left(errors))
    }

    def getTypeInfo(pkg: PPkg): Option[Either[Vector[VerifierError], ExternalTypeInfo]] = contextMap.get(pkg) match {
      case s@Some(_) => s
      case _ => {
        // there is no entry yet and package resolution might need to resolve multiple depending packages
        // keep track of these packages in pendingPackages until either type information or an error is added to contextMap
        if (pendingPackages.contains(pkg)) {
          // package cycle detected
          knownImportCycles += pendingPackages
          Some(Left(Vector(CyclicImportError(s"Cyclic package import detected starting with package '$pkg'"))))
        } else {
          pendingPackages = pendingPackages :+ pkg
          None
        }
      }
    }

    /**
      * Returns all package names that lie on the cycle of imports or none if no cycle was found
      */
    def getImportCycle(pkg: PPkg): Option[Vector[PPkg]] = knownImportCycles.find(_.contains(pkg))

    def getContexts: Iterable[ExternalTypeInfo] = contextMap.values.collect { case Right(info) => info }

    def getExternalErrors: Vector[VerifierError] = contextMap.values.collect { case Left(errs) => errs }.flatten.toVector
  }

  def check(pkg: PPackage, context: Context = new Context)(config: Config): Future[Either[Vector[VerifierError], TypeInfo with ExternalTypeInfo]] = {
    Future {
      val tree = new GoTree(pkg)
      //    println(program.declarations.head)
      //    println("-------------------")
      //    println(tree)
      val info = new TypeInfoImpl(tree, context)(config: Config)

      // get errors and remove duplicates as errors related to imported packages might occur multiple times
    // consider this: each error in an imported package is converted to an error at the import node with
    // message 'Package <pkg name> contains errors'. If the imported package contains 2 errors then only a single error
    // should be reported at the import node instead of two.
    val errors = info.errors.distinct
      config.reporter report TypeCheckDebugMessage(config.inputFiles.head, () => pkg, () => getDebugInfo(pkg, info))
      if (errors.isEmpty) {
        config.reporter report TypeCheckSuccessMessage(config.inputFiles.head, () => pkg, () => getErasedGhostCode(pkg, info), () => getGoifiedGhostCode(program, info))
        Right(info)
      } else {
        val typeErrors = pkg.positions.translate(errors, TypeError)
        config.reporter report TypeCheckFailureMessage(config.inputFiles.head, pkg.packageClause.id.name, () => pkg, typeErrors)
        Left(typeErrors)
      }
    }

  }

  private def getErasedGhostCode(pkg: PPackage, info: TypeInfoImpl): String = {
    new GhostLessPrinter(info).format(pkg)
  }

  private def getGoifiedGhostCode(program: PProgram, info: TypeInfoImpl): String = {
    new GoifyingPrinter(info).format(program)
  }

  private def getDebugInfo(pkg: PPackage, info: TypeInfoImpl): String = {
    new InfoDebugPrettyPrinter(info).format(pkg)
  }
}

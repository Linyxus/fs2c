package fs2c.codegen

import fs2c.codegen.{CodeBundles => bd}
import fs2c.ast.Symbol
import fs2c.ast.c.{ Trees => C }
import fs2c.ast.fs.{ Trees => FS }
import fs2c.typer.{ Types => FST }
import FS.tpd
import FS.{ExprBinOpType => sbop}
import C.{BinOpType => cbop}
import fs2c.tools.Unique

class CodeGen {
  import CodeGen.CodeGenError

  /** Context for C code generator.
    */
  val ctx = new CodeGenContext

  /** Preloaded standard definitions.
    */
  val stdLib = new StdLibrary(this)

  /** Mangle name by adding postfix.
    */
  def mangle(name: String): String = Unique.uniqueCName(name)

  /** Generate a fresh anonymous function name.
    */
  def freshAnonFuncName: String = Unique.uniqueCName("anon_func")

  /** Generate a fresh anonymous function type name.
    */
  def freshAnonFuncTypeName: String = Unique.uniqueCName("anon_func_type")

  /** Generate a fresh variable name.
    */
  def freshVarName: String = Unique.uniqueCName("temp")

  /** Output definition. Record the definition and return it as it is.
    */
  def outDef[T <: C.Definition](d: => T): T = {
    ctx.generatedDefs = d :: ctx.generatedDefs
    d
  }

  def localAllocDef(name: String, tp: C.StructType): (Symbol[C.VariableDef], C.Block) = {
    val expr = useMalloc $$ C.SizeOf(tp)

    defn.localVariable(name, tp, Some(expr))
  }

  def maybeAllocLocalDef(name: String, tp: C.Type): (Symbol[C.VariableDef], C.Block) = {
    tp match {
      case tp: C.StructType => localAllocDef(name, tp)
      case tp => defn.localVariable(name, tp)
    }
  }

  /** Declare the usage of a ground function by adding its headers into included header list.
    * 
    * @param func The ground function to use.
    * @return The used ground function, returned as it is.
    */
  def useGroundFunc(func: C.GroundFunc): C.GroundFunc = {
    val includes = func.header
    ctx.addHeaders(includes)
    func
  }
  
  def useMalloc: C.GroundFunc = useGroundFunc(defn.GroundFuncs.malloc)

  /** Make a structure definition.
    * 
    * @param name The struct name.
    * @param memberDefs Member definitions.
    * @return
    */
  def makeStructDef(name: String, memberDefs: List[(String, C.Type)]): C.StructDef = outDef {
    C.StructDef.makeStructDef(name, memberDefs)
  }

  /** Make a type alias definition with given `name` and `dealias.`
    * ```c
    * type name = dealias;
    * ```
    * 
    * @param name The name to alias the type to.
    * @param dealias The aliased name.
    * @return Type alias definition.
    */
  def makeAliasDef(name: String, dealias: C.Type): C.TypeAliasDef = outDef {
    val res = C.TypeAliasDef.makeTypeAliasDef(name, dealias)
    res
  }

  /** Make and output a function definition.
    */
  def makeFuncDef(name: String, valueType: C.Type, params: List[C.FuncParam], body: C.Block): C.FuncDef = outDef {
    C.FuncDef.makeFuncDef(name, valueType, params, body)
  }

  /** Generate C type for Scala type.
    * 
    * @param tp The input Scala type.
    * @param aliasName Alias definition name. It will be passed if a alias needed to be created. 
    *                  See [[CodeGen.genLambdaType]].
    * @return Generated C code bundle for the given type.
    */
  def genType(tp: FST.Type, aliasName: Option[String] = None, lambdaValueType: Boolean = false): bd.TypeBundle = tp.assignCode {
    tp match {
      case FST.GroundType.IntType => bd.SimpleTypeBundle {
        C.BaseType.IntType
      }
      case FST.GroundType.FloatType => bd.SimpleTypeBundle {
        C.BaseType.DoubleType
      }
      case FST.GroundType.BooleanType => bd.SimpleTypeBundle {
        C.BaseType.IntType
      }
      case _ : FST.LambdaType if lambdaValueType => lambdaClosureType
      case FST.LambdaType(paramTypes, valueType) => genLambdaType(paramTypes, valueType, aliasName)
    }
  }
  
  def lambdaClosureType: bd.SimpleTypeBundle = {
    val structDef = stdLib.FuncClosure.load
    bd.SimpleTypeBundle { C.StructType(structDef.sym) }
  }

  /** Generate C code bundle for a LambdaType in Scala.
    */
  def genLambdaType(paramTypes: List[FST.Type], valueType: FST.Type, aliasName: Option[String]): bd.AliasTypeBundle = {
    val name = aliasName getOrElse freshAnonFuncTypeName

    val tps = paramTypes map { t => genType(t, None).getTp }
    val vTp = genType(valueType, None).getTp
    
    val d: C.TypeAliasDef = maybeAliasFuncType(defn.funcType(defn.VoidPointer :: tps, vTp), name)
    
    bd.AliasTypeBundle(C.AliasType(d.sym), d)
  }

  /** Maybe generate alias definition for `funcType`.
    * It will first looks up `funcType` in cache, return the generated type alias definition if already generated,
    * output a new type alias definition otherwise.
    * 
    * @param funcType 
    * @param aliasName Name for the alias definition.
    * @return
    */
  def maybeAliasFuncType(funcType: C.FuncType, aliasName: String): C.TypeAliasDef =
    ctx.genFuncCache.get(funcType) match {
      case Some(alias) => alias
      case None => 
        val res = makeAliasDef(aliasName, funcType)
        ctx.genFuncCache = ctx.genFuncCache.updated(funcType, res)
        res
    }

  /** Generate C code for Scala class definition.
    *
    * @param clsDef
    * @return
    */
  def genClassDef(clsDef: tpd.ClassDef): bd.ClassBundle = clsDef assignCode { case FS.ClassDef(sym, params, _, members) =>
    ???
  }

  def genClassStructDef(sym: Symbol[_], members: List[tpd.MemberDef]): C.StructDef = {
    val res = makeStructDef(
      name = sym.name + "_struct",
      memberDefs = members map { m =>
        val name = m.tree.sym.name
        val tp = genType(m.tpe, lambdaValueType = true)
        name -> tp.getTp
      }
    )

    // assign generated struct member to typed tree
    members foreach { m =>
      m assignCode { m =>
        val name = m.sym.name
        val cMem = res.ensureFind(name)
        bd.PureExprBundle(C.IdentifierExpr(cMem.sym))
      }
    }

    res
  }

  def genClassMethod(sym: Symbol[_],
                     memberName: String, lambda: tpd.LambdaExpr,
                     structDef: C.StructDef, structValue: C.IdentifierExpr[C.VariableDef]): bd.ClosureBundle = {
    val escaped = lambda.freeNames filter { sym =>
      sym.dealias match {
        case tpt: FS.Typed[_] => tpt.tree match {
          case t: FS.MemberDef[_] => false
          case _ => true
        }
        case _ => true
      }
    }

    // filter lambda free names
    lambda.freeNames = escaped

    genLambdaExpr(lambda, lambdaName = Some(s"${sym.name}_${memberName}"), self = Some((structDef, structValue))).asInstanceOf
  }

  /** Generate C code for Scala expressions.
    */
  def genExpr(expr: tpd.Expr, lambdaName: Option[String] = None): bd.ValueBundle = expr.tree match {
    case _ : FS.LiteralIntExpr[FS.Typed] => genIntLiteralExpr(expr.asInstanceOf)
    case _ : FS.LiteralFloatExpr[FS.Typed] => genFloatLiteralExpr(expr.asInstanceOf)
    case _ : FS.LiteralBooleanExpr[FS.Typed] => genBooleanLiteralExpr(expr.asInstanceOf)
    case _ : FS.IdentifierExpr[FS.Typed] => genIdentifierExpr(expr.asInstanceOf)
    case _ : FS.BinOpExpr[FS.Typed] => genBinaryOpExpr(expr.asInstanceOf)
    case _ : FS.IfExpr[FS.Typed] => genIfExpr(expr.asInstanceOf)
    case _ : FS.BlockExpr[FS.Typed] => genBlockExpr(expr.asInstanceOf)
    case _ : FS.LambdaExpr[FS.Typed] => genLambdaExpr(expr.asInstanceOf, lambdaName = lambdaName)
    case _ : FS.ApplyExpr[FS.Typed] => genApplyExpr(expr.asInstanceOf)
    case _ => throw CodeGenError(s"unsupported expr $expr")
  }

  /** Generate code for int literals.
    */
  def genIntLiteralExpr(expr: tpd.LiteralIntExpr): bd.PureExprBundle = expr.assignCode { t =>
    val code = bd.PureExprBundle(C.IntExpr(t.value))
    code
  }

  /** Generate code for float literals.
    */
  def genFloatLiteralExpr(expr: tpd.LiteralFloatExpr): bd.PureExprBundle = expr.assignCode { t =>
    val code = bd.PureExprBundle(C.FloatExpr(t.value))
    code
  }

  /** Generate code for boolean literals.
    */
  def genBooleanLiteralExpr(expr: tpd.LiteralBooleanExpr): bd.PureExprBundle = expr.assignCode { t =>
    val code = bd.PureExprBundle(C.BoolExpr(t.value))
    code
  }

  /** Generate code for a Scala identifier. It will extract the corresponding C definition of
    * the referred Scala identifier. Closure environment will also be considered.
    */
  def genIdentifierExpr(expr: tpd.IdentifierExpr): bd.PureExprBundle = expr.assignCode { t =>
    t.sym match {
      case resolved : Symbol.Ref.Resolved[_] => 
        val sym = resolved.sym
        
        ctx.refClosureEnv(sym) match {
          case None =>
          case Some(expr) => return bd.PureExprBundle(expr)
        }
        
        sym.dealias match {
          case lambdaParam: FS.LambdaParam =>
            lambdaParam.code match {
              case bundle : bd.ValueBundle => bd.PureExprBundle(bundle.getExpr)
              case _ => assert(false, s"unexpected code bundle for lambda param: ${lambdaParam.code}")
            }
          case tpt : FS.Typed[_] => tpt.code match {
            case bundle : bd.VariableBundle =>
              bd.PureExprBundle(C.IdentifierExpr(bundle.varDef.sym))
            case _ =>
              throw CodeGenError(s"unsupported referenced typed tree: $tpt with generated code ${tpt.code}")
          }
          case x =>
            throw CodeGenError(s"unsupported reference identifier: ${t.sym} with reference ${resolved.sym.dealias}")
        }
      case _ =>
        assert(false, "encounter unresolved symbol in code generator, this is a bug.")
    }
  }

  /** Generate code for a binary expression.
    */
  def genBinaryOpExpr(expr: tpd.BinOpExpr): bd.ValueBundle = expr.assignCode { case FS.BinOpExpr(op, e1, e2) =>
    val cop = sbOp2cbOp(op)
    val bd1 = genExpr(e1)
    val bd2 = genExpr(e2)

    val cExpr = C.BinOpExpr(cop, bd1.getExpr, bd2.getExpr)
    val cBlock = bd1.getBlock ++ bd2.getBlock

    if cBlock.nonEmpty then
      bd.BlockBundle(cExpr, cBlock)
    else
      bd.PureExprBundle(cExpr)
  }

  val sbopEq = sbop.==
  val sbopNeq = sbop.!=
  def sbOp2cbOp(op: sbop): cbop = op match {
    case sbop.+ => cbop.+
    case sbop.- => cbop.-
    case sbop.* => cbop.*
    case sbop./ => cbop./
    case sbop.^ => cbop.^
    case sbop.&& => cbop.&&
    case sbop.|| => cbop.||
    case sbop.>= => cbop.>=
    case sbop.<= => cbop.<=
    case sbop.> => cbop.>
    case sbop.< => cbop.<
    case _ if op == sbopEq => cbop.==
    case _ if op == sbopNeq => cbop.!=
  }

  /** Generate C code for If expression.
    */
  def genIfExpr(expr: tpd.IfExpr): bd.BlockBundle = expr.assignCode { case FS.IfExpr(cond, et, ef) =>
    val (tempVar, tempDef) = defn.localVariable(freshVarName, C.BaseType.IntType)
    
    val bdCond = genExpr(cond)
    
    val condExpr = bdCond.getExpr
    val condBlock = bdCond.getBlock
    
    val bdt = genExpr(et)
    val bdf = genExpr(ef)
    
    val tBlock = bdt.getBlock :+ defn.assignVar(tempVar.dealias, bdt.getExpr)
    val fBlock = bdf.getBlock :+ defn.assignVar(tempVar.dealias, bdf.getExpr)
    
    val ifStmt = C.Statement.If(condExpr, tBlock, Some(fBlock))
    
    bd.BlockBundle(
      expr = C.IdentifierExpr(tempVar),
      block = tempDef ++ condBlock :+ ifStmt
    )
  }

  /** Mangle `name` if now we are not at top level (to prevent possible naming conflicts), and do nothing if we are
    * at the top level.
    */
  def maybeMangleName(name: String): String =
    if ctx.isTopLevel then
      name
    else
      mangle(name)

  /** Generate code for block expression.
    */
  def genBlockExpr(expr: tpd.BlockExpr): bd.BlockBundle = ctx.innerLevel {
    expr.assignCode { case blockExpr : FS.BlockExpr[FS.Typed] =>
      def goRecLocalDef(localDef: tpd.LocalDef): Unit =
        if localDef.tree.isLambdaBind then
          localDef assignCode {
            case d : FS.LocalDef.Bind[FS.Typed] =>
              bd.RecBundle(sym = Symbol[C.FuncDef](maybeMangleName(d.sym.name), null))
            case _ =>
              assert(false, "a lambda binding must be a FS.LocalDef.Bind")
          }

      def genLocalDef(localDef: tpd.LocalDef): bd.CodeBundle & bd.HasBlock = localDef assignCode {
        case bind : FS.LocalDef.Bind[FS.Typed] =>
          val name: String = localDef.code match {
            case bd.RecBundle(sym) => sym.name
            case _ => maybeMangleName(bind.sym.name)
          }

          genExpr(bind.body, lambdaName = Some(name + "_lambda")) match {
            case bundle: bd.ClosureBundle =>
              val (varDef, varBlock) =
                defn.localVariable(
                  name = name,
                  tp = genType(localDef.tpe, lambdaValueType = true).getTp,
                  expr = Some(bundle.getExpr)
                )

              bd.VariableBundle(
                varDef = varDef.dealias,
                block = bundle.getBlock ++ varBlock
              )
            case bundle =>
              val (varDef, varBlock) = defn.localVariable(name, genType(localDef.tpe, lambdaValueType = true).getTp)

              val assignStmt = defn.assignVar(varDef.dealias, bundle.getExpr)

              varDef.dealias.associatedBundle = Some(bundle)

              bd.VariableBundle(
                varDef = varDef.dealias,
                block = (bundle.getBlock ++ varBlock) :+ assignStmt
              )
          }
        case eval : FS.LocalDef.Eval[FS.Typed] =>
          genExpr(eval.expr)
        case assign : FS.LocalDef.Assign[FS.Typed] =>
          val sym: Symbol[_] = assign.ref match {
            case ref : Symbol.Ref.Resolved[_] => ref.sym
            case _ =>
              assert(false, "unresolved symbol reference in typed tree. a bug!")
          }
          val bodyBd: bd.ValueBundle = genExpr(assign.expr)

          val assignStmt: C.Statement = sym.dealias match {
            case tpt : FS.Typed[_] =>
              tpt.code match {
                case bundle : bd.VariableBundle =>
                  defn.assignVar(bundle.varDef, bodyBd.getExpr)
                case bundle =>
                  throw CodeGenError(s"unexpected code bundle ${tpt.code} of assigned symbol")
              }
            case _ => assert(false, "symbol refers to untyped tree")
          }

          bd.PureBlockBundle(bodyBd.getBlock :+ assignStmt)
      }

      val localDefs = blockExpr.defs

      // assign placeholder recursive code bundle for each lambda binding
      localDefs foreach goRecLocalDef

      // translate local definitions
      val cBlockBundles = localDefs map genLocalDef

      // translated block
      val block1 = cBlockBundles flatMap { x => x.extractBlock }

      // translate the final expression
      val exprBundle: bd.ValueBundle = genExpr(blockExpr.expr)
      
      val (defBlock, otherBlock) =
        ((block1 ++ exprBundle.getBlock).groupBy {
          case _: C.Statement.Def => true
          case _ => false
        }) match { case m => (m(true), m(false)) }

      bd.BlockBundle(
        expr = exprBundle.getExpr,
        block = defBlock ++ otherBlock
      )
    }
  }

  def genApplyExpr(expr: tpd.ApplyExpr): bd.ValueBundle = expr.assignCode {
    case apply: FS.ApplyExpr[FS.Typed] =>
      def extractFunc(expr: tpd.Expr): bd.ClosureBundle = ???

      val funcBundle: bd.ValueBundle = genExpr(apply.func)
      val argsBundle: List[bd.ValueBundle] = apply.args map { arg => genExpr(arg) }

      val funcExpr = funcBundle.getExpr
      val funcBlock = funcBundle.getBlock
      val argBlock = argsBundle flatMap { b => b.getBlock }
      val argExpr = argsBundle map (_.getExpr)

      val selectFunc = C.SelectExpr(funcExpr, stdLib.FuncClosure.load.ensureFind("func").sym)
      val selectEnv = C.SelectExpr(funcExpr, stdLib.FuncClosure.load.ensureFind("env").sym)

      val funcType: FST.LambdaType = apply.func.tpe match {
        case tp: FST.LambdaType => tp
        case _ => assert(false, "can not apply a non-lambda expr, this is cause by a bug in typer")
      }

      val cFuncType = genLambdaType(funcType.paramTypes, funcType.valueType, None).tpe
      val func = C.CoercionExpr(cFuncType, selectFunc)

      val callExpr = C.CallFunc(func, params = selectEnv :: argExpr)

      bd.BlockBundle(
        expr = callExpr,
        block = funcBlock ++ argBlock
      )
  }

  /** Generate code for lambda expressions. It will create a closure to lift a lambda expression with local references
    * to top level, while will generate a simple function definition if otherwise.
    * 
    * @param expr
    * @param lambdaName Optional name. Create a new name for anonymous function if not given.
    * @return
    */
  def genLambdaExpr(expr: tpd.LambdaExpr, lambdaName: Option[String] = None,
                    self: Option[(C.StructDef, C.IdentifierExpr[C.VariableDef])] = None): bd.ValueBundle = expr.assignCode {
    case lambda : FS.LambdaExpr[FS.Typed] =>
      val funcName = lambdaName getOrElse freshAnonFuncName

      // generate function return type
      val lambdaType: FST.LambdaType = expr.tpe.asInstanceOf
      val valueType = lambdaType.valueType
      val cValueType: C.Type = genType(valueType, lambdaValueType = true).getTp

      // generate parameter definitions
      val cParams: List[C.FuncParam] = lambda.params map { sym => genLambdaParam(sym.dealias) }

      // compute escaped variables
      val escaped: List[Symbol[tpd.LocalDefBind] | Symbol[FS.LambdaParam]] = escapedVars(expr.freeNames)

      escaped match {
        case Nil if false =>
          val bodyBundle: bd.ValueBundle = genExpr(lambda.body)
          val block = bodyBundle.getBlock :+ C.Statement.Return(Some(bodyBundle.getExpr))
          val funcDef = makeFuncDef(
            funcName,
            cValueType,
            cParams,
            block
          )
          val identExpr = C.IdentifierExpr(funcDef.sym)

          bd.SimpleFuncBundle(identExpr, funcDef)
        case escaped =>
          var envMembers = escaped map { sym =>
            sym.name -> genType(sym.dealias match {
              case p : FS.LambdaParam => p.tpe
              case p : tpd.LocalDefBind => p.tpe
            }, lambdaValueType = true).getTp
          }
          var selfName = ""
          self foreach { (selfDef, self) =>
            selfName = mangle("self")
            envMembers = (selfName -> C.StructType(selfDef.sym)) :: envMembers
          }
          val funcEnv = createClosureEnv(envMembers, funcName)

          def initClosureEnv: (C.Expr, C.Block) = {
            val (tempVar, varBlock) = maybeAllocLocalDef(freshVarName, funcEnv.tp)

            val assignBlock = escaped map { sym =>
              sym.dealias match {
                case lambdaParam : FS.LambdaParam =>
                  val name = lambdaParam.sym.name
                  val cMember = funcEnv.members find { m => m.sym.name == name } match {
                    case None =>
                      assert(false, "escaped variable should be found in closure env")
                    case Some(x) => x
                  }
                  defn.assignMember(tempVar.dealias, cMember.sym, lambdaParam.code.asInstanceOf[bd.PureExprBundle].expr)
                case tptBind : tpd.LocalDefBind =>
                  tptBind.code match {
                    case bd.NoCode =>
                      throw CodeGenError(s"code $tptBind has not been generated; can not forward-reference")
                    case bd : bd.VariableBundle =>
                      val name = tptBind.tree.sym.name
                      val cMember = funcEnv.members find { m => m.sym.name == name } match {
                        case None =>
                          assert(false, "escaped variable should be found in closure env")
                        case Some(x) => x
                      }
                      defn.assignMember(tempVar.dealias, cMember.sym, C.IdentifierExpr(bd.varDef.sym))
                    case bd : bd.RecBundle[_] =>
                      val name = tptBind.tree.sym.name
                      val cMember = funcEnv.members find { m => m.sym.name == name } match {
                        case None =>
                          assert(false, "escaped variable should be found in closure env")
                        case Some(x) => x
                      }
                      defn.assignMember(tempVar.dealias, cMember.sym, C.IdentifierExpr(bd.sym))
                  }
              }
            }

            val assignSelfBlock = self match {
              case None => Nil
              case Some((selfDef, self)) =>
                List(defn.assignMember(self.sym.dealias, funcEnv.ensureFind(selfName).sym, self))
            }

            C.IdentifierExpr(tempVar) -> (varBlock ++ assignBlock ++ assignSelfBlock)
          }

          def initClosure(func: C.Expr, env: C.Expr): (C.Expr, C.Block) = {
            val closureDef: C.StructDef = stdLib.FuncClosure.load

            val (tempVar, varDef) = maybeAllocLocalDef(s"${funcName}_closure", closureDef.tp)

            val block = varDef ++ List(
              defn.assignMember(tempVar.dealias, closureDef.ensureFind("func").sym, func),
              defn.assignMember(tempVar.dealias, closureDef.ensureFind("env").sym, env),
            )

              C.IdentifierExpr(tempVar) -> block
          }

          val (funcExpr, funcDef) = ctx.inClosure(escaped, funcEnv) {
            def selfWrapper[T](body: => T) = self match {
              case None => body
              case Some((selfDef, self)) => ctx.withSelf(funcEnv.ensureFind(selfName).sym, selfDef)(body)
            }

            def res =
              // get the final function param
              val cParams2 = ctx.getClosureEnvParam :: cParams
              val (envVar, tpEnvDef) = defn.localVariable(
                "my_env",
                C.StructType(funcEnv.sym),
                Some(C.IdentifierExpr(ctx.getClosureEnvParam.sym))
              )
              ctx.setClosureEnvVar(envVar.dealias)
              val bodyBundle: bd.ValueBundle = genExpr(lambda.body)
              val block = bodyBundle.getBlock :+ C.Statement.Return(Some(bodyBundle.getExpr))
              val funcDef = makeFuncDef(
                funcName,
                cValueType,
                cParams2,
                tpEnvDef ++ block
              )

              val identExpr = C.IdentifierExpr(funcDef.sym)

              (identExpr, funcDef)
            selfWrapper { res }
          }

          val (envExpr, envBlock) = initClosureEnv

          val (finalExpr, closureBlock) = initClosure(funcExpr, envExpr)

          bd.ClosureBundle(
            expr = finalExpr,
            block = envBlock ++ closureBlock,
            envStructDef = funcEnv,
            funcDef = funcDef
          )
      }
  }

  /** Translate a Scala [[fs2c.ast.fs.Trees.LambdaParam]] to C [[fs2c.ast.c.Trees.FuncParam]].
    * 
    * @param param
    * @return
    */
  def genLambdaParam(param: FS.LambdaParam): C.FuncParam = {
    val res = param match { case FS.LambdaParam(sym, tp, _) =>
      val cTp: C.Type = genType(tp, lambdaValueType = true).getTp
      
      val res = C.FuncParam(Symbol(sym.name, null), cTp)
      res.sym.dealias = res
        
      res
    }
    
    param.code = bd.PureExprBundle(C.IdentifierExpr(res.sym))
    
    res
  }

  /** Create a closure environment for function with local references.
    */
  def createClosureEnv(env: List[(String, C.Type)], funcName: String): C.StructDef = makeStructDef(
    name = funcName + "_env",
    memberDefs = env
  )

  /** Compute escaped variables from a free name list.
    */
  def escapedVars(freeNames: List[Symbol[_]]): List[Symbol[tpd.LocalDefBind] | Symbol[FS.LambdaParam]] = {
    def recur(xs: List[Symbol[_]], acc: List[Symbol[tpd.LocalDefBind]]): List[Symbol[tpd.LocalDefBind]] = xs match {
      case Nil => acc
      case x :: xs => x.dealias match {
        case tpt : FS.Typed[_] => tpt.tree match {
          case localDef: FS.LocalDef.Bind[_] =>
            recur(xs, x.asInstanceOf :: acc)
          case _ => recur(xs, acc)
        }
        case lambdaParam: FS.LambdaParam => recur(xs, x.asInstanceOf :: acc)
        case _ => recur(xs, acc)
      }
    }
    
    recur(freeNames, Nil).distinctBy(eq)
  }
  
}

object CodeGen {

  case class CodeGenError(msg: String) extends Exception(s"Code generation error: $msg")

}

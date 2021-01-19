// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobra.translator.implementations.translator

import viper.gobra.ast.{internal => in}
import viper.gobra.frontend.info.base.BuiltInMemberTag
import viper.gobra.frontend.info.base.BuiltInMemberTag.{BuiltInFPredicateTag, BuiltInFunctionTag, BuiltInMPredicateTag, BuiltInMemberTag, BuiltInMethodTag, ChannelInvariantMethodTag, CloseFunctionTag, ClosedMPredTag, ClosureDebtMPredTag, CreateDebtChannelMethodTag, InitChannelMethodTag, IsChannelMPredTag, PredTrueFPredTag, RecvChannelMPredTag, RecvGivenPermMethodTag, RecvPermMethodTag, RedeemChannelMethodTag, SendChannelMPredTag, SendGotPermMethodTag, SendPermMethodTag, TokenMPredTag}
import viper.gobra.reporting.Source
import viper.gobra.theory.Addressability
import viper.gobra.translator.Names
import viper.gobra.translator.interfaces.translator.BuiltInMembers
import viper.gobra.translator.interfaces.{Collector, Context}
import viper.gobra.translator.util.PrimitiveGenerator
import viper.gobra.util.Computation
import viper.gobra.util.Violation.violation

import scala.language.postfixOps


/**
  * Encodes built-in members by translating them to 'regular' members and calling the corresponding encoding
  */
class BuiltInMembersImpl extends BuiltInMembers {

  // the implementation uses 4 distinct generators (instead of a single one) such that the exposed
  // methods (i.e. method, function, fpredicate, and mpredicate) can return the translated 'regular' member.

  override def finalize(col: Collector): Unit = {
    methodGenerator.finalize(col)
    functionGenerator.finalize(col)
    fPredicateGenerator.finalize(col)
    mPredicateGenerator.finalize(col)
  }

  private def member(x: in.BuiltInMember)(ctx: Context): in.Member =
    x match {
      case m: in.BuiltInMethod => methodGenerator(m, ctx)
      case f: in.BuiltInFunction => functionGenerator(f, ctx)
      case p: in.BuiltInFPredicate => fPredicateGenerator(p, ctx)
      case p: in.BuiltInMPredicate => mPredicateGenerator(p, ctx)
    }

  override def method(x: in.BuiltInMethod)(ctx: Context): in.MethodMember =
    methodGenerator(x, ctx)

  override def function(x: in.BuiltInFunction)(ctx: Context): in.FunctionMember =
    functionGenerator(x, ctx)

  override def fpredicate(x: in.BuiltInFPredicate)(ctx: Context): in.FPredicate =
    fPredicateGenerator(x, ctx)

  override def mpredicate(x: in.BuiltInMPredicate)(ctx: Context): in.MPredicate =
    mPredicateGenerator(x, ctx)

  private val methodGenerator: PrimitiveGenerator.PrimitiveGenerator[(in.BuiltInMethod, Context), in.MethodMember] = PrimitiveGenerator.simpleGenerator {
    case (bm: in.BuiltInMethod, ctx: Context) =>
      val meth = translateMethod(bm)(ctx)
      val m = meth match {
        case meth: in.Method => ctx.method.method(meth)(ctx).res
        case meth: in.PureMethod => ctx.pureMethod.pureMethod(meth)(ctx).res
      }
      (meth, Vector(m))
  }

  private val functionGenerator: PrimitiveGenerator.PrimitiveGenerator[(in.BuiltInFunction, Context), in.FunctionMember] = PrimitiveGenerator.simpleGenerator {
    case (bf: in.BuiltInFunction, ctx: Context) =>
      val func = translateFunction(bf)(ctx)
      val f = func match {
        case func: in.Function => ctx.method.function(func)(ctx).res
        case func: in.PureFunction => ctx.pureMethod.pureFunction(func)(ctx).res
      }
      (func, Vector(f))
  }

  private val fPredicateGenerator: PrimitiveGenerator.PrimitiveGenerator[(in.BuiltInFPredicate, Context), in.FPredicate] = PrimitiveGenerator.simpleGenerator {
    case (bp: in.BuiltInFPredicate, ctx: Context) =>
      val pred = translateFPredicate(bp)(ctx)
      val p = ctx.predicate.fpredicate(pred)(ctx).res
      (pred, Vector(p))
  }

  private val mPredicateGenerator: PrimitiveGenerator.PrimitiveGenerator[(in.BuiltInMPredicate, Context), in.MPredicate] = PrimitiveGenerator.simpleGenerator {
    case (bp: in.BuiltInMPredicate, ctx: Context) =>
      val pred = translateMPredicate(bp)(ctx)
      val p = ctx.predicate.mpredicate(pred)(ctx).res
      (pred, Vector(p))
  }

  /**
    * Returns an already existing proxy for a tag or otherwise creates a new proxy and the corresponding member
    */
  private def getOrGenerateMethod(tag: BuiltInMethodTag, recv: in.Type, args: Vector[in.Type])(src: Source.Parser.Info)(ctx: Context): in.MethodProxy = {
    def create(): in.BuiltInMethod = {
      val proxy = in.MethodProxy(tag.identifier, freshNameForTag(tag))(src)
      in.BuiltInMethod(recv, tag, proxy, args)(src)
    }
    val method = getOrGenerate(tag, Vector(recv), create)(ctx)
    method.name
  }

  /**
    * Returns an already existing proxy for a tag or otherwise creates a new proxy and the corresponding member
    */
  private def getOrGenerateFunction(tag: BuiltInFunctionTag, args: Vector[in.Type])(src: Source.Parser.Info)(ctx: Context): in.FunctionProxy = {
    def create(): in.BuiltInFunction = {
      val proxy = in.FunctionProxy(freshNameForTag(tag))(src)
      in.BuiltInFunction(tag, proxy, args)(src)
    }
    val function = getOrGenerate(tag, args, create)(ctx)
    function.name
  }

  /**
    * Returns an already existing proxy for a tag or otherwise creates a new proxy and the corresponding member
    */
  private def getOrGenerateFPredicate(tag: BuiltInFPredicateTag, args: Vector[in.Type])(src: Source.Parser.Info)(ctx: Context): in.FPredicateProxy = {
    def create(): in.BuiltInFPredicate = {
      val proxy = in.FPredicateProxy(freshNameForTag(tag))(src)
      in.BuiltInFPredicate(tag, proxy, args)(src)
    }
    val predicate = getOrGenerate(tag, args, create)(ctx)
    predicate.name
  }

  /**
    * Returns an already existing proxy for a tag or otherwise creates a new proxy and the corresponding member
    */

  private def getOrGenerateMPredicate(tag: BuiltInMPredicateTag, recv: in.Type, args: Vector[in.Type])(src: Source.Parser.Info)(ctx: Context): in.MPredicateProxy = {
    def create(): in.BuiltInMPredicate = {
      val proxy = in.MPredicateProxy(tag.identifier, freshNameForTag(tag))(src)
      in.BuiltInMPredicate(recv, tag, proxy, args)(src)
    }
    val predicate = getOrGenerate(tag, Vector(recv), create)(ctx)
    predicate.name
  }

  /**
    * stores all members that are not part of the lookup table but have been created because of a dependency of other built-in members
    */
  private var additionalMembers: Map[(BuiltInMemberTag, Vector[in.Type]), in.BuiltInMember] = Map.empty

  /**
    * Generic method to retrieve a built-in member or generate a new one in case it does not exist yet
    * @param createMember function that creates a new member (without encoding it)
    */
  private def getOrGenerate[T <: BuiltInMemberTag, M <: in.BuiltInMember](tag: T, args: Vector[in.Type], createMember: () => M)(ctx: Context): M = {
    def generate: M = {
      val m = createMember()
      additionalMembers = additionalMembers + ((tag, args) -> m)
      // encode member:
      member(m)(ctx)
      m
    }

    val members: Map[(T, Vector[in.Type]), M] = (lookupTableMembers(ctx) ++ additionalMembers) collect {
      case ((t: T@unchecked, as), m: M@unchecked) => (t, as) -> m
    }
    members.getOrElse((tag, args), generate)
  }

  /**
    * Returns all built-in members stored in the lookup table. The resulting map is cached for faster repeated
    * accesses since the lookup table cannot be modified.
    */
  private def lookupTableMembers(ctx: Context): Map[(BuiltInMemberTag, Vector[in.Type]), in.BuiltInMember] =
    Computation.cachedComputation[Context, Map[(BuiltInMemberTag, Vector[in.Type]), in.BuiltInMember]](ctx => {
      (ctx.table.getMethods ++ ctx.table.getFunctions ++ ctx.table.getFPredicates ++ ctx.table.getMPredicates) collect {
        case p: in.BuiltInMethod => (p.tag, Vector(p.receiverT)) -> p
        case f: in.BuiltInFunction => (f.tag, f.argsT) -> f
        case f: in.BuiltInFPredicate => (f.tag, f.argsT) -> f
        case p: in.BuiltInMPredicate => (p.tag, Vector(p.receiverT)) -> p
      } toMap
    })(ctx)


  private def freshNameForTag(tag: BuiltInMemberTag): String =
    s"${Names.builtInMember}_${tag.identifier}_${Names.freshName}"


  //
  // Translation functions that translate a built-in member to a 'regular' member.
  // This 'regular' member is then encoded as if it is a user-provided member.
  //

  private def translateMethod(x: in.BuiltInMethod)(ctx: Context): in.MethodMember = {
    val src = x.info
    (x.tag, x.receiverT) match {
      case (tag: ChannelInvariantMethodTag, recv: in.ChannelT) =>
        /**
          * requires acc(c.[chanPredicateTag](), _)
          * pure func (c chan T).[tag.identifier] (res [returnType])
          *
          * where
          *   - chanPredicateTag is either SendChannel or RecvChannel
          *   - returnType is either pred(T) or pred()
          */
        assert(recv.addressability == Addressability.inParameter)
        val recvParam = in.Parameter.In("c", recv)(src)
        val resType = tag match {
          case SendGotPermMethodTag | RecvGivenPermMethodTag => in.PredT(Vector(), Addressability.outParameter) // pred()
          case _ => in.PredT(Vector(recv.elem), Addressability.outParameter) // pred(T)
        }
        val resParam = in.Parameter.Out("res", resType.withAddressability(Addressability.outParameter))(src)
        val chanPredicateTag = tag match {
          case _: SendPermMethodTag => BuiltInMemberTag.SendChannelMPredTag
          case _: RecvPermMethodTag => BuiltInMemberTag.RecvChannelMPredTag
        }
        val chanPredicate = builtInMPredAccessible(chanPredicateTag, recvParam, Vector())(src)(ctx)
        val pres: Vector[in.Assertion] = Vector(
          in.Access(chanPredicate, in.WildcardPerm(src))(src)
        )
        in.PureMethod(recvParam, x.name, Vector(), Vector(resParam), pres, Vector(), None)(src)

      case (InitChannelMethodTag, recv: in.ChannelT) =>
        /**
          * requires c.IsChannel(k)
          * requires k > 0 ==> (B == pred_true{} && C == pred_true{})
          * requires A == D && B == C
          * ensures c.SendChannel() && c.RecvChannel()
          * ensures c.SendGivenPerm() == A && c.SendGotPerm() == B
          * ensures c.RecvGivenPerm() == C && c.RecvGotPerm() == D
          * ghost func (c chan T).Init(k int, A pred(T), B pred(), C pred(), D pred(T))
          *
          * note that B is only of type pred() instead of pred(T) as long as we cannot deal with view shifts in Gobra.
          * as soon as we can, the third precondition can be changed to `[v] ((A(v) && C()) ==> (B(v) && D(v)))`
          */
        assert(recv.addressability == Addressability.inParameter)
        val recvParam = in.Parameter.In("c", recv)(src)
        val kParam = in.Parameter.In("k", in.IntT(Addressability.inParameter))(src)
        val predTType = in.PredT(Vector(recv.elem), Addressability.inParameter) // pred(T)
        val predType = in.PredT(Vector(), Addressability.inParameter) // pred()
        val aParam = in.Parameter.In("A", predTType)(src)
        val bParam = in.Parameter.In("B", predType)(src)
        val cParam = in.Parameter.In("C", predType)(src)
        val dParam = in.Parameter.In("D", predTType)(src)
        val isChannelInst = builtInMPredAccessible(BuiltInMemberTag.IsChannelMPredTag, recvParam, Vector(kParam))(src)(ctx)
        val predTrueProxy = getOrGenerateFPredicate(BuiltInMemberTag.PredTrueFPredTag, Vector())(src)(ctx)
        val predTrueConstr = in.PredicateConstructor(predTrueProxy, predType, Vector())(src) // pred_true{}
        val bufferedImpl = in.Implication(
          in.GreaterCmp(kParam, in.IntLit(0)(src))(src),
          in.SepAnd(
            in.ExprAssertion(in.EqCmp(bParam, predTrueConstr)(src))(src),
            in.ExprAssertion(in.EqCmp(cParam, predTrueConstr)(src))(src)
          )(src)
        )(src)
        val trivialPermExchange = in.And(
          in.EqCmp(aParam, dParam)(src),
          in.EqCmp(bParam, cParam)(src)
        )(src)
        val pres: Vector[in.Assertion] = Vector(
          in.Access(isChannelInst, in.FullPerm(src))(src),
          bufferedImpl,
          in.ExprAssertion(trivialPermExchange)(src)
        )
        val sendChannelInst = builtInMPredAccessible(BuiltInMemberTag.SendChannelMPredTag, recvParam, Vector())(src)(ctx)
        val recvChannelInst = builtInMPredAccessible(BuiltInMemberTag.RecvChannelMPredTag, recvParam, Vector())(src)(ctx)
        val sendAndRecvChannel = in.SepAnd(
          in.Access(sendChannelInst, in.FullPerm(src))(src),
          in.Access(recvChannelInst, in.FullPerm(src))(src)
        )(src)
        val sendGivenPermCall = builtInPureMethodCall(BuiltInMemberTag.SendGivenPermMethodTag, recvParam, Vector(), predTType)(src)(ctx)
        val sendGivenPermEq = in.EqCmp(sendGivenPermCall, aParam)(src)
        val sendGotPermCall = builtInPureMethodCall(BuiltInMemberTag.SendGotPermMethodTag, recvParam, Vector(), predType)(src)(ctx)
        val sendGotPermEq = in.EqCmp(sendGotPermCall, bParam)(src)
        val sendChannelInvEq = in.And(sendGivenPermEq, sendGotPermEq)(src)
        val recvGivenPermCall = builtInPureMethodCall(BuiltInMemberTag.RecvGivenPermMethodTag, recvParam, Vector(), predType)(src)(ctx)
        val recvGivenPermEq = in.EqCmp(recvGivenPermCall, cParam)(src)
        val recvGotPermCall = builtInPureMethodCall(BuiltInMemberTag.RecvGotPermMethodTag, recvParam, Vector(), predTType)(src)(ctx)
        val recvGotPermEq = in.EqCmp(recvGotPermCall, dParam)(src)
        val recvChannelInvEq = in.And(recvGivenPermEq, recvGotPermEq)(src)
        val posts: Vector[in.Assertion] = Vector(
          sendAndRecvChannel,
          in.ExprAssertion(sendChannelInvEq)(src),
          in.ExprAssertion(recvChannelInvEq)(src),
        )
        in.Method(recvParam, x.name, Vector(kParam, aParam, bParam, cParam, dParam), Vector(), pres, posts, None)(src)

      case (CreateDebtChannelMethodTag, recv: in.ChannelT) =>
        /**
          * requires divisor > 0
          * requires acc(c.SendChannel(), dividend/divisor /* p */)
          * ensures c.ClosureDebt(P, dividend, divisor /* p */) && c.Token(P)
          * ghost func (c chan T) CreateDebt(dividend int, divisor int /* p perm */, P pred())
          */
        assert(recv.addressability == Addressability.inParameter)
        val recvParam = in.Parameter.In("c", recv)(src)
        val dividendParam = in.Parameter.In("dividend", in.IntT(Addressability.inParameter))(src)
        val divisorParam = in.Parameter.In("divisor", in.IntT(Addressability.inParameter))(src)
        // val permissionAmountParam = in.Parameter.In("p", in.PermissionT(Addressability.inParameter))(src)
        val predicateParam = in.Parameter.In("P", in.PredT(Vector(), Addressability.inParameter))(src)
        val sendChannelInst = builtInMPredAccessible(BuiltInMemberTag.SendChannelMPredTag, recvParam, Vector())(src)(ctx)
        val pres: Vector[in.Assertion] = Vector(
          in.ExprAssertion(in.GreaterCmp(divisorParam, in.IntLit(0)(src))(src))(src),
          in.Access(sendChannelInst, in.FractionalPerm(dividendParam, divisorParam)(src))(src)
          // in.Access(sendChannelInst, permissionAmountParam)(src)
        )
        val closureDebtArgs = Vector(predicateParam, dividendParam, divisorParam /* permissionAmountParam */)
        val closureDebtInst = builtInMPredAccessible(BuiltInMemberTag.ClosureDebtMPredTag, recvParam, closureDebtArgs)(src)(ctx)
        val tokenInst = builtInMPredAccessible(BuiltInMemberTag.TokenMPredTag, recvParam, Vector(predicateParam))(src)(ctx)
        val posts: Vector[in.Assertion] = Vector(
          in.Access(closureDebtInst, in.FullPerm(src))(src),
          in.Access(tokenInst, in.FullPerm(src))(src),
        )
        in.Method(recvParam, x.name, Vector(dividendParam, divisorParam /* permissionAmountParam */, predicateParam), Vector(), pres, posts, None)(src)

      case (RedeemChannelMethodTag, recv: in.ChannelT) =>
        /**
          * requires c.Token(P) && acc(c.Closed(), _)
          * ensures c.Closed() && P()
          * ghost func (c chan T) Redeem(P pred())
          *
          * note that c.Closed() is duplicable and thus full permission can be ensured
          */
        assert(recv.addressability == Addressability.inParameter)
        val recvParam = in.Parameter.In("c", recv)(src)
        val predicateParam = in.Parameter.In("P", in.PredT(Vector(), Addressability.inParameter))(src)
        val tokenInst = builtInMPredAccessible(BuiltInMemberTag.TokenMPredTag, recvParam, Vector(predicateParam))(src)(ctx)
        val closedInst = builtInMPredAccessible(BuiltInMemberTag.ClosedMPredTag, recvParam, Vector())(src)(ctx)
        val pres: Vector[in.Assertion] = Vector(
          in.Access(tokenInst, in.FullPerm(src))(src),
          in.Access(closedInst, in.WildcardPerm(src))(src)
        )
        val posts: Vector[in.Assertion] = Vector(
          in.Access(closedInst, in.FullPerm(src))(src),
          in.Access(in.Accessible.PredExpr(in.PredExprInstance(predicateParam, Vector())(src)), in.FullPerm(src))(src)
        )
        in.Method(recvParam, x.name, Vector(predicateParam), Vector(), pres, posts, None)(src)

      case (tag, recv) => violation(s"no method generation defined for tag $tag and receiver $recv")
    }
  }

  private def translateFunction(x: in.BuiltInFunction)(ctx: Context): in.FunctionMember = {
    val src = x.info
    (x.tag, x.argsT) match {
      case (CloseFunctionTag, Vector(channelT, dividendT, divisorT /* permissionAmountT */, predicateT)) =>
        /**
          * requires divisor > 0
          * requires acc(c.SendChannel(), dividene/divisor /* p */) && c.ClosureDebt(P, divisor - dividend, divisor /* 1-p */) && P()
          * ensures c.Closed()
          * func close(c chan T, ghost dividend int, divisor int /* p perm */, P pred())
          */
        assert(channelT.addressability == Addressability.inParameter)
        val channelParam = in.Parameter.In("c", channelT)(src)
        assert(dividendT.addressability == Addressability.inParameter)
        val dividendParam = in.Parameter.In("dividend", dividendT)(src)
        assert(divisorT.addressability == Addressability.inParameter)
        val divisorParam = in.Parameter.In("divisor", divisorT)(src)
        // assert(permissionAmountT.addressability == Addressability.inParameter)
        // val permissionAmountParam = in.Parameter.In("p", permissionAmountT)(src)
        val predicateParam = in.Parameter.In("P", predicateT)(src)
        val args = Vector(channelParam, dividendParam, divisorParam /* permissionAmountParam */, predicateParam)
        val sendChannelInst = builtInMPredAccessible(BuiltInMemberTag.SendChannelMPredTag, channelParam, Vector())(src)(ctx)
        val closureDebtArgs = Vector(
          predicateParam,
          in.Sub(divisorParam, dividendParam)(src),
          divisorParam
          // in.Sub(in.IntLit(1)(src), permissionAmountParam)(src)
        )
        val closureDebtInst = builtInMPredAccessible(BuiltInMemberTag.ClosureDebtMPredTag, channelParam, closureDebtArgs)(src)(ctx)
        val pres: Vector[in.Assertion] = Vector(
          in.ExprAssertion(in.GreaterCmp(divisorParam, in.IntLit(0)(src))(src))(src),
          in.Access(sendChannelInst, in.FractionalPerm(dividendParam, divisorParam)(src))(src),
          // in.Access(sendChannelInst, permissionAmountParam)(src),
          in.Access(closureDebtInst, in.FullPerm(src))(src),
          in.Access(in.Accessible.PredExpr(in.PredExprInstance(predicateParam, Vector())(src)), in.FullPerm(src))(src)
        )
        val closedInst = builtInMPredAccessible(BuiltInMemberTag.ClosedMPredTag, channelParam, Vector())(src)(ctx)
        val posts: Vector[in.Assertion] = Vector(
          in.Access(closedInst, in.FullPerm(src))(src)
        )
        in.Function(x.name, args, Vector(), pres, posts, None)(src)
      case (tag, args) => violation(s"no function generation defined for tag $tag and arguments $args")
    }
  }

  private def translateFPredicate(x: in.BuiltInFPredicate)(ctx: Context): in.FPredicate = {
    val src = x.info
    (x.tag, x.argsT) match {
      case (PredTrueFPredTag, args) =>
        /**
          * pred PredTrue([args]) {
          *   true
          * }
          */
        val params = args.zipWithIndex.map {
          case (arg, idx) =>
            assert(arg.addressability == Addressability.inParameter)
            in.Parameter.In(s"arg$idx", arg)(src)
        }
        val body: Option[in.Assertion] = Some(in.ExprAssertion(in.BoolLit(true)(src))(src))
        in.FPredicate(x.name, params, body)(src)
      case (tag, args) => violation(s"no fpredicate generation defined for tag $tag and arguments $args")
    }
  }

  private def translateMPredicate(x: in.BuiltInMPredicate)(ctx: Context): in.MPredicate = {
    val src = x.info
    assert(x.receiverT.addressability == Addressability.inParameter)
    val recvParam = in.Parameter.In("c", x.receiverT)(src)

    x.tag match {
      case IsChannelMPredTag =>
        /**
          * pred (c chan T) IsChannel(k Int)
          */
        val bufferSizeParam = in.Parameter.In("k", in.IntT(Addressability.inParameter))(src)
        in.MPredicate(recvParam, x.name, Vector(bufferSizeParam), None)(src)
      case SendChannelMPredTag | RecvChannelMPredTag | ClosedMPredTag =>
        /**
          * pred (c chan T) [tag.identifier]()
          */
        in.MPredicate(recvParam, x.name, Vector(), None)(src)
      case ClosureDebtMPredTag =>
        /**
          * pred (c chan T) ClosureDebt(P pred(), dividend int, divisor int /* p perm */)
          */
        val predicateParam = in.Parameter.In("P", in.PredT(Vector(), Addressability.inParameter))(src)
        val dividendParam = in.Parameter.In("dividend", in.IntT(Addressability.inParameter))(src)
        val divisorParam = in.Parameter.In("divisor", in.IntT(Addressability.inParameter))(src)
        // val permissionAmountParam = in.Parameter.In("p", in.PermissionT(Addressability.inParameter))(src)
        in.MPredicate(recvParam, x.name, Vector(predicateParam, dividendParam, divisorParam /* permissionAmountParam */), None)(src)
      case TokenMPredTag =>
        /**
          * pred (c chan T) Token(P pred())
          */
        val predicateParam = in.Parameter.In("P", in.PredT(Vector(), Addressability.inParameter))(src)
        in.MPredicate(recvParam, x.name, Vector(predicateParam), None)(src)
      case tag => violation(s"no mpredicate generation defined for tag $tag")
    }
  }

  /**
    * Helper method to create an accessible predicate for a built-in mpredicate
    */
  private def builtInMPredAccessible(tag: BuiltInMPredicateTag, recv: in.Expr, args: Vector[in.Expr])(src: Source.Parser.Info)(ctx: Context): in.Accessible.Predicate = {
    val proxy = getOrGenerateMPredicate(tag, recv.typ, args.map(_.typ))(src)(ctx)
    in.Accessible.Predicate(in.MPredicateAccess(recv, proxy, args)(src))
  }

  /**
    * Helper method to create a pure method call to a built-in method
    */
  private def builtInPureMethodCall(tag: BuiltInMethodTag, recv: in.Expr, args: Vector[in.Expr], retType: in.Type)(src: Source.Parser.Info)(ctx: Context): in.PureMethodCall = {
    val method = getOrGenerateMethod(tag, recv.typ, args.map(_.typ))(src)(ctx)
    in.PureMethodCall(recv, method, args, retType)(src)
  }

}

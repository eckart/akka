/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import java.lang.reflect.{InvocationTargetException, Method}
import java.net.InetSocketAddress

import reactor.{MessageDispatcher, FutureResult}
import nio.protobuf.RemoteProtocol.{RemoteRequest, RemoteReply}
import nio.{RemoteProtocolBuilder, RemoteClient, RemoteServer, RemoteRequestIdFactory}
import config.ScalaConfig._
import util._
import serialization.Serializer

import org.codehaus.aspectwerkz.intercept.{Advisable, AroundAdvice, Advice}
import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy
import org.codehaus.aspectwerkz.annotation.{Aspect, Around}
import org.codehaus.aspectwerkz.aspect.management.Aspects

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)

object Annotations {
  import se.scalablesolutions.akka.annotation._
  val oneway =              classOf[oneway]
  val transactionrequired = classOf[transactionrequired]
  val prerestart =          classOf[prerestart]
  val postrestart =         classOf[postrestart]
  val immutable =           classOf[immutable]
}

/**
 * Factory for Java API.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectFactory {

  // FIXME How to pass the MessageDispatcher on from active object to child???????

  def newInstance[T](target: Class[T], timeout: Long): T =
    ActiveObject.newInstance(target, new Dispatcher(None), None, timeout)

  def newInstance[T](target: Class[T], timeout: Long, restartCallbacks: Option[RestartCallbacks]): T =
    ActiveObject.newInstance(target, new Dispatcher(restartCallbacks), None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long): T =
    ActiveObject.newInstance(intf, target, new Dispatcher(None), None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, restartCallbacks: Option[RestartCallbacks]): T =
    ActiveObject.newInstance(intf, target, new Dispatcher(restartCallbacks), None, timeout)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int): T =
    ActiveObject.newInstance(target, new Dispatcher(None), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    ActiveObject.newInstance(target, new Dispatcher(restartCallbacks), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int): T =
    ActiveObject.newInstance(intf, target, new Dispatcher(None), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    ActiveObject.newInstance(intf, target, new Dispatcher(restartCallbacks), Some(new InetSocketAddress(hostname, port)), timeout)

  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(target, actor, None, timeout)
  }

  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(target, actor, None, timeout)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(intf, target, actor, None, timeout)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(intf, target, actor, None, timeout)
  }

  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    ActiveObject.newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  private[akka] def newInstance[T](target: Class[T], actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    ActiveObject.newInstance(target, actor, remoteAddress, timeout)
  }

  private[akka] def newInstance[T](intf: Class[T], target: AnyRef, actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    ActiveObject.newInstance(intf, target, actor, remoteAddress, timeout)
  }
  
  private[akka] def supervise(restartStrategy: RestartStrategy, components: List[Supervise]): Supervisor =
    ActiveObject.supervise(restartStrategy, components)

  /*
  def newInstanceAndLink[T](target: Class[T], supervisor: AnyRef): T = {
    val actor = new Dispatcher(None)(target.getName)
    ActiveObject.newInstance(target, actor)
  }

  def newInstanceAndLink[T](intf: Class[T], target: AnyRef, supervisor: AnyRef): T = {
    val actor = new Dispatcher(None)(target.getName)
    ActiveObject.newInstance(intf, target, actor)
  }
  */
}

/**
 * Factory for Scala API.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {

  val MATCH_ALL = "execution(* *.*(..))"
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  def newInstance[T](target: Class[T], timeout: Long): T =
    newInstance(target, new Dispatcher(None), None, timeout)

  def newInstance[T](target: Class[T], timeout: Long, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(target, new Dispatcher(restartCallbacks), None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long): T =
    newInstance(intf, target, new Dispatcher(None), None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(intf, target, new Dispatcher(restartCallbacks), None, timeout)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int): T =
    newInstance(target, new Dispatcher(None), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(target, new Dispatcher(restartCallbacks), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int): T =
    newInstance(intf, target, new Dispatcher(None), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(intf, target, new Dispatcher(restartCallbacks), Some(new InetSocketAddress(hostname, port)), timeout)

  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher(None)
    actor.messageDispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = new Dispatcher(restartCallbacks)
    actor.messageDispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  private[akka] def newInstance[T](target: Class[T], actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    //if (getClass.getClassLoader.getResourceAsStream("META-INF/aop.xml") != null) println("000000000000000000000 FOUND AOP")
    if (remoteAddress.isDefined) actor.makeRemote(remoteAddress.get)
    val proxy = Proxy.newInstance(target, false, true)
    actor.initialize(target, proxy)
    actor.timeout = timeout
    actor.start
    AspectInitRegistry.register(proxy, AspectInit(target, actor, remoteAddress, timeout))
    proxy.asInstanceOf[T]
  }

  private[akka] def newInstance[T](intf: Class[T], target: AnyRef, actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    //if (getClass.getClassLoader.getResourceAsStream("META-INF/aop.xml") != null) println("000000000000000000000 FOUND AOP")
    if (remoteAddress.isDefined) actor.makeRemote(remoteAddress.get)
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    actor.initialize(target.getClass, target)
    actor.timeout = timeout
    actor.start
    AspectInitRegistry.register(proxy, AspectInit(intf, actor, remoteAddress, timeout))
    proxy.asInstanceOf[T]
  }


  private[akka] def supervise(restartStrategy: RestartStrategy, components: List[Supervise]): Supervisor = {
    object factory extends SupervisorFactory {
      override def getSupervisorConfig = SupervisorConfig(restartStrategy, components)
    }
    val supervisor = factory.newSupervisor
    supervisor ! StartSupervisor
    supervisor
  }
}

object AspectInitRegistry {
  private val inits = new java.util.concurrent.ConcurrentHashMap[AnyRef, AspectInit]
  def initFor(target: AnyRef) = {
    val init = inits.get(target)
    inits.remove(target)
    init
  }  
  def register(target: AnyRef, init: AspectInit) = inits.put(target, init)
}

sealed case class AspectInit(
  val target: Class[_],
  val actor: Dispatcher,          
  val remoteAddress: Option[InetSocketAddress],
  val timeout: Long)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
@Aspect("perInstance")
sealed class ActiveObjectAspect {
  @volatile var isInitialized = false
  var target: Class[_] = _
  var actor: Dispatcher = _            
  var remoteAddress: Option[InetSocketAddress] = _
  var timeout: Long = _

  @Around("execution(* *..*(..))")
  def invoke(joinpoint: JoinPoint): AnyRef = {
    if (!isInitialized) {
      val init = AspectInitRegistry.initFor(joinpoint.getThis)
      target = init.target
      actor = init.actor            
      remoteAddress = init.remoteAddress
      timeout = init.timeout
      isInitialized = true
    }
    dispatch(joinpoint)
  }

  private def dispatch(joinpoint: JoinPoint) = {
    if (remoteAddress.isDefined) remoteDispatch(joinpoint)
    else localDispatch(joinpoint)
  }

  private def localDispatch(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    if (isOneWay(rtti)) actor ! Invocation(joinpoint, true)
    else {
      val result = actor !! Invocation(joinpoint, false)
      if (result.isDefined) result.get
      else throw new IllegalStateException("No result defined for invocation [" + joinpoint + "]")
    }
  }

  private def remoteDispatch(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    val oneWay = isOneWay(rtti)
    val (message: Array[AnyRef], isEscaped) = escapeArguments(rtti.getParameterValues)
    val requestBuilder = RemoteRequest.newBuilder
      .setId(RemoteRequestIdFactory.nextId)
      .setMethod(rtti.getMethod.getName)
      .setTarget(target.getName)
      .setTimeout(timeout)
      .setIsActor(false)
      .setIsOneWay(oneWay)
      .setIsEscaped(false)
    RemoteProtocolBuilder.setMessage(message, requestBuilder)
    val id = actor.registerSupervisorAsRemoteActor
    if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
    val remoteMessage = requestBuilder.build
    val future = RemoteClient.clientFor(remoteAddress.get).send(remoteMessage)
    if (oneWay) null // for void methods
    else {
      if (future.isDefined) {
        future.get.await
        val result = getResultOrThrowException(future.get)
        if (result.isDefined) result.get
        else throw new IllegalStateException("No result returned from call to [" + joinpoint + "]")
      } else throw new IllegalStateException("No future returned from call to [" + joinpoint + "]")
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw cause
    } else future.result.asInstanceOf[Option[T]]
  
  private def isOneWay(rtti: MethodRtti) =
    rtti.getMethod.getReturnType == java.lang.Void.TYPE ||
    rtti.getMethod.isAnnotationPresent(Annotations.oneway)

  private def escapeArguments(args: Array[AnyRef]): Tuple2[Array[AnyRef], Boolean] = {
    var isEscaped = false
    val escapedArgs = for (arg <- args) yield {
      val clazz = arg.getClass
      if (clazz.getName.contains("$$ProxiedByAW")) {
        isEscaped = true
        "$$ProxiedByAW" + clazz.getSuperclass.getName
      } else arg
    }
    (escapedArgs, isEscaped)
  }
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable private[akka] case class Invocation(val joinpoint: JoinPoint, val isOneWay: Boolean) {

  override def toString: String = synchronized {
    "Invocation [joinpoint: " + joinpoint.toString + ", isOneWay: " + isOneWay + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, joinpoint)
    result = HashCode.hash(result, isOneWay)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].joinpoint == joinpoint &&
    that.asInstanceOf[Invocation].isOneWay == isOneWay
  }
}

/**
 * Generic Actor managing Invocation dispatch, transaction and error management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class Dispatcher(val callbacks: Option[RestartCallbacks]) extends Actor {
  private val ZERO_ITEM_CLASS_ARRAY = Array[Class[_]]()
  private val ZERO_ITEM_OBJECT_ARRAY = Array[Object[_]]()

  private[actor] var target: Option[AnyRef] = None
  private var preRestart: Option[Method] = None
  private var postRestart: Option[Method] = None

  private[actor] def initialize(targetClass: Class[_], targetInstance: AnyRef) = {
    if (targetClass.isAnnotationPresent(Annotations.transactionrequired)) makeTransactionRequired
    id = targetClass.getName
    target = Some(targetInstance)
    val methods = targetInstance.getClass.getDeclaredMethods.toList

    // See if we have any config define restart callbacks
    callbacks match {
      case None => {}
      case Some(RestartCallbacks(pre, post)) =>
        preRestart = Some(try {
          targetInstance.getClass.getDeclaredMethod(pre, ZERO_ITEM_CLASS_ARRAY: _*)
        } catch { case e => throw new IllegalStateException("Could not find pre restart method [" + pre + "] in [" + targetClass.getName + "]. It must have a zero argument definition.") })
        postRestart = Some(try {
          targetInstance.getClass.getDeclaredMethod(post, ZERO_ITEM_CLASS_ARRAY: _*)
        } catch { case e => throw new IllegalStateException("Could not find post restart method [" + post + "] in [" + targetClass.getName + "]. It must have a zero argument definition.") })
    }

    // See if we have any annotation defined restart callbacks 
    if (!preRestart.isDefined) preRestart = methods.find( m => m.isAnnotationPresent(Annotations.prerestart))
    if (!postRestart.isDefined) postRestart = methods.find( m => m.isAnnotationPresent(Annotations.postrestart))

    if (preRestart.isDefined && preRestart.get.getParameterTypes.length != 0)
      throw new IllegalStateException("Method annotated with @prerestart or defined as a restart callback in [" + targetClass.getName + "] must have a zero argument definition")
    if (postRestart.isDefined && postRestart.get.getParameterTypes.length != 0)
      throw new IllegalStateException("Method annotated with @postrestart or defined as a restart callback in [" + targetClass.getName + "] must have a zero argument definition")

    if (preRestart.isDefined) preRestart.get.setAccessible(true)
    if (postRestart.isDefined) postRestart.get.setAccessible(true)
  }

  override def receive: PartialFunction[Any, Unit] = {
    case Invocation(joinpoint, oneWay) =>
      if (Actor.SERIALIZE_MESSAGES) serializeArguments(joinpoint)
      if (oneWay) joinpoint.proceed
      else reply(joinpoint.proceed)
    case unexpected =>
      throw new ActiveObjectException("Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }

  override protected def preRestart(reason: AnyRef, config: Option[AnyRef]) {
    try {
      if (preRestart.isDefined) preRestart.get.invoke(target.get, ZERO_ITEM_OBJECT_ARRAY: _*)
    } catch { case e: InvocationTargetException => throw e.getCause }
  }

  override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
    try {
      if (postRestart.isDefined) postRestart.get.invoke(target.get, ZERO_ITEM_OBJECT_ARRAY: _*)
    } catch { case e: InvocationTargetException => throw e.getCause }
  }

  private def serializeArguments(joinpoint: JoinPoint) = {
    val args = joinpoint.getRtti.asInstanceOf[MethodRtti].getParameterValues
    var unserializable = false
    var hasMutableArgument = false
    for (arg <- args.toList) {
      if (!arg.isInstanceOf[String] &&
        !arg.isInstanceOf[Byte] &&
        !arg.isInstanceOf[Int] &&
        !arg.isInstanceOf[Long] &&
        !arg.isInstanceOf[Float] &&
        !arg.isInstanceOf[Double] &&
        !arg.isInstanceOf[Boolean] &&
        !arg.isInstanceOf[Char] &&
        !arg.isInstanceOf[java.lang.Byte] &&
        !arg.isInstanceOf[java.lang.Integer] &&
        !arg.isInstanceOf[java.lang.Long] &&
        !arg.isInstanceOf[java.lang.Float] &&
        !arg.isInstanceOf[java.lang.Double] &&
        !arg.isInstanceOf[java.lang.Boolean] &&
        !arg.isInstanceOf[java.lang.Character] &&
        !arg.getClass.isAnnotationPresent(Annotations.immutable)) {
        hasMutableArgument = true
      }
      if (arg.getClass.getName.contains("$$ProxiedByAWSubclassing$$")) unserializable = true
    }
    if (!unserializable && hasMutableArgument) {
      // FIXME: can we have another default deep cloner?
      val copyOfArgs = Serializer.Java.deepClone(args)
      joinpoint.getRtti.asInstanceOf[MethodRtti].setParameterValues(copyOfArgs.asInstanceOf[Array[AnyRef]])
    }    
  }
}

/*
ublic class CamelInvocationHandler implements InvocationHandler {
     private final Endpoint endpoint;
    private final Producer producer;
    private final MethodInfoCache methodInfoCache;

    public CamelInvocationHandler(Endpoint endpoint, Producer producer, MethodInfoCache methodInfoCache) {
        this.endpoint = endpoint;
        this.producer = producer;
        this.methodInfoCache = methodInfoCache;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        BeanInvocation invocation = new BeanInvocation(method, args);
        ExchangePattern pattern = ExchangePattern.InOut;
        MethodInfo methodInfo = methodInfoCache.getMethodInfo(method);
        if (methodInfo != null) {
            pattern = methodInfo.getPattern();
        }
        Exchange exchange = new DefaultExchange(endpoint, pattern);
        exchange.getIn().setBody(invocation);

        producer.process(exchange);
        Throwable fault = exchange.getException();
        if (fault != null) {
            throw new InvocationTargetException(fault);
        }
        if (pattern.isOutCapable()) {
            return exchange.getOut().getBody();
        } else {
            return null;
        }
    }
}

      if (joinpoint.target.isInstanceOf[MessageDriven] &&
          joinpoint.method.getName == "onMessage") {
        val m = joinpoint.method

      val endpointName = m.getDeclaringClass.getName + "." + m.getName
        val activeObjectName = m.getDeclaringClass.getName
        val endpoint = conf.getRoutingEndpoint(conf.lookupUriFor(m))
        val producer = endpoint.createProducer
        val exchange = endpoint.createExchange
        exchange.getIn().setBody(joinpoint)
        producer.process(exchange)
        val fault = exchange.getException();
        if (fault != null) throw new InvocationTargetException(fault)

        // FIXME: need some timeout and future here...
        exchange.getOut.getBody

      } else
*/

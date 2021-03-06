/*
 * Copyright 2009-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert
package network
package netty

import org.jboss.netty.channel.group.ChannelGroup
import org.jboss.netty.channel._
import server.{MessageExecutor, MessageHandlerRegistry, RequestContext => NorbertRequestContext}
import common.CachedNetworkStatistics
import protos.NorbertProtos
import logging.Logging
import java.util.UUID
import jmx.JMX.MBean
import org.jboss.netty.handler.codec.oneone.{OneToOneEncoder, OneToOneDecoder}
import jmx.{FinishedRequestTimeTracker, JMX}
import java.lang.String
import com.google.protobuf.{ByteString}
import norbertutils._
import util.ProtoUtils


case class RequestContext(requestId: UUID, receivedAt: Long = System.currentTimeMillis) extends NorbertRequestContext

@ChannelPipelineCoverage("all")
class RequestContextDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: Any) = {
    val norbertMessage = msg.asInstanceOf[NorbertProtos.NorbertMessage]
    val requestId = new UUID(norbertMessage.getRequestIdMsb, norbertMessage.getRequestIdLsb)

    if (norbertMessage.getStatus != NorbertProtos.NorbertMessage.Status.OK) {
      val ex = new InvalidMessageException("Invalid request, message has status set to ERROR")
      Channels.write(ctx, Channels.future(channel), ResponseHelper.errorResponse(requestId, ex))
      throw ex
    }

    (RequestContext(requestId), norbertMessage)
  }
}

@ChannelPipelineCoverage("all")
class RequestContextEncoder extends OneToOneEncoder with Logging {
  def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any) = {
    val (context, norbertMessage) = msg.asInstanceOf[(RequestContext, NorbertProtos.NorbertMessage)]

    norbertMessage
  }
}

@ChannelPipelineCoverage("all")
class ServerFilterChannelHandler(messageExecutor: MessageExecutor) extends SimpleChannelHandler with Logging {
  override def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    if (e.isInstanceOf[MessageEvent]) {
      val (context, norbertMessage) = e.asInstanceOf[MessageEvent].getMessage.asInstanceOf[(RequestContext, NorbertProtos.NorbertMessage)]
      messageExecutor.filters.foreach { filter =>
        filter match {
          case f : NettyServerFilter => continueOnError(f.onMessage(norbertMessage, context))
        }
      }
    }
    super.handleUpstream(ctx, e)
  }

  override def handleDownstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    if (e.isInstanceOf[MessageEvent]) {
      val (context, norbertMessage) = e.asInstanceOf[MessageEvent].getMessage.asInstanceOf[(RequestContext, NorbertProtos.NorbertMessage)]
      messageExecutor.filters.reverse.foreach { filter =>
        filter match {
          case f :NettyServerFilter => continueOnError(f.postMessage(norbertMessage, context))
        }
      }
    }
    super.handleDownstream(ctx, e)
  }
}

@ChannelPipelineCoverage("all")
class ServerChannelHandler(clientName: Option[String],
                           serviceName: String,
                           channelGroup: ChannelGroup,
                           messageHandlerRegistry: MessageHandlerRegistry,
                           messageExecutor: MessageExecutor,
                           requestStatisticsWindow: Long,
                           avoidByteStringCopy: Boolean) extends SimpleChannelHandler with Logging {
  private val statsActor = CachedNetworkStatistics[Int, UUID](SystemClock, requestStatisticsWindow, 200L)

  val statsJmx = JMX.register(new NetworkServerStatisticsMBeanImpl(clientName, serviceName, statsActor))

  def shutdown: Unit = {
    statsJmx.foreach { JMX.unregister(_) }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val channel = e.getChannel
    log.trace("channelOpen: " + channel)
    channelGroup.add(channel)
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val (context, norbertMessage) = e.getMessage.asInstanceOf[(RequestContext, NorbertProtos.NorbertMessage)]
    val channel = e.getChannel

    val messageName = norbertMessage.getMessageName
    val requestBytes = ProtoUtils.byteStringToByteArray(norbertMessage.getMessage, avoidByteStringCopy)

    statsActor.beginRequest(0, context.requestId, 0)

    val (handler, is, os) = try {
      val handler: Any => Any = messageHandlerRegistry.handlerFor(messageName)
      val is: InputSerializer[Any, Any] = messageHandlerRegistry.inputSerializerFor(messageName)
      val os: OutputSerializer[Any, Any] = messageHandlerRegistry.outputSerializerFor(messageName)

      (handler, is, os)
    } catch {
      case ex: InvalidMessageException =>
        Channels.write(ctx, Channels.future(channel), (context, ResponseHelper.errorResponse(context.requestId, ex)))
        statsActor.endRequest(0, context.requestId)

        throw ex
    }

    val request = is.requestFromBytes(requestBytes)

    try {
      messageExecutor.executeMessage(request, Option((either: Either[Exception, Any]) => {
        responseHandler(context, e.getChannel, either)(is, os)
      }), Some(context))(is)
    }
    catch {
      case ex: HeavyLoadException =>
        Channels.write(ctx, Channels.future(channel), (context, ResponseHelper.errorResponse(context.requestId, ex, NorbertProtos.NorbertMessage.Status.HEAVYLOAD)))
        statsActor.endRequest(0, context.requestId) 
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) = log.info(e.getCause, "Caught exception in channel: %s".format(e.getChannel))

  def responseHandler[RequestMsg, ResponseMsg](context: RequestContext, channel: Channel, either: Either[Exception, ResponseMsg])
  (implicit is: InputSerializer[RequestMsg, ResponseMsg], os: OutputSerializer[RequestMsg, ResponseMsg]) {
    val response = either match {
      case Left(ex) => ResponseHelper.errorResponse(context.requestId, ex)
      case Right(responseMsg) =>
        ResponseHelper.responseBuilder(context.requestId)
        .setMessageName(os.responseName)
        .setMessage(ProtoUtils.byteArrayToByteString(os.responseToBytes(responseMsg), avoidByteStringCopy))
        .build
    }

    log.debug("Sending response: %s".format(response))

    channel.write((context, response))

    statsActor.endRequest(0, context.requestId)
  }
}

private[netty] object ResponseHelper {
  def responseBuilder(requestId: UUID) = {
    NorbertProtos.NorbertMessage.newBuilder.setRequestIdMsb(requestId.getMostSignificantBits).setRequestIdLsb(requestId.getLeastSignificantBits)
  }

  def errorResponse(requestId: UUID, ex: Exception, status: NorbertProtos.NorbertMessage.Status = NorbertProtos.NorbertMessage.Status.ERROR) = {
    responseBuilder(requestId)
            .setMessageName(ex.getClass.getName)
            .setStatus(status)
            .setErrorMessage(if (ex.getMessage == null) "" else ex.getMessage)
            .build
  }
}

trait NetworkServerStatisticsMBean {
  def getRequestsPerSecond: Int
  def getAverageRequestProcessingTime: Double
  def getMedianTime: Double
  def get90PercentileTime: Double
  def get99PercentileTime: Double


  def getAverageResponseProcessingTime: Double
  def getMedianResponseTime: Double
  def get90PercentileResponseTime: Double
  def get99PercentileResponseTime: Double
}

class NetworkServerStatisticsMBeanImpl(clientName: Option[String], serviceName: String, val stats: CachedNetworkStatistics[Int, UUID])
  extends MBean(classOf[NetworkServerStatisticsMBean], JMX.name(clientName, serviceName)) with NetworkServerStatisticsMBean {

  def toMillis(statsMetric: Double):Double = {statsMetric/1000} 

  def getMedianTime = toMillis((stats.getStatistics(0.5).map(_.finished.values.map(_.percentile)).flatten.sum))

  def getRequestsPerSecond = (stats.getStatistics(0.5).map(_.rps().values).flatten.sum)

  def getAverageRequestProcessingTime = stats.getStatistics(0.5).map { stats =>
    val total = stats.finished.values.map(_.total).sum
    val size = stats.finished.values.map(_.size).sum

    toMillis(safeDivide(total.toDouble, size)(0.0))
  } getOrElse(0.0)

  def get90PercentileTime = toMillis((stats.getStatistics(0.90).map(_.finished.values.map(_.percentile)).flatten.sum))

  def get99PercentileTime = toMillis((stats.getStatistics(0.99).map(_.finished.values.map(_.percentile)).flatten.sum)) 

  //the following statistics are in microseconds not milliseconds
  def getAverageResponseProcessingTime = stats.getStatistics(0.5).map { stats =>
    val total = stats.finishedResponse.values.map(_.total).sum
    val size = stats.finishedResponse.values.map(_.size).sum

    safeDivide(total.toDouble, size)(0.0)
  } getOrElse(0.0)

  def getMedianResponseTime = stats.getStatistics(0.5).map(_.finishedResponse.values.map(_.percentile)).flatten.sum

  def get90PercentileResponseTime = stats.getStatistics(0.90).map(_.finishedResponse.values.map(_.percentile)).flatten.sum

  def get99PercentileResponseTime = stats.getStatistics(0.99).map(_.finishedResponse.values.map(_.percentile)).flatten.sum

  def getAverageQueueTime = stats.getStatistics(0.5).map { stats =>
    val total = stats.finishedQueueTime.values.map(_.total).sum
    val size = stats.finishedQueueTime.values.map(_.size).sum

    safeDivide(total.toDouble, size)(0.0)
  } getOrElse (0.0)
}


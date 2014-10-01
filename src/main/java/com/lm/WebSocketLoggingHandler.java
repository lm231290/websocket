package com.lm;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.logging.Level;
import java.util.logging.Logger;

public class WebSocketLoggingHandler extends ChannelDuplexHandler {
  private static Logger log = Logger.getLogger(WebSocketServer.class.getName());

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof TextWebSocketFrame) {
      log.log(Level.INFO, "{0} received {1}", new Object[]{ctx.channel(), ((TextWebSocketFrame) msg).text()});
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (msg instanceof TextWebSocketFrame) {
      log.log(Level.INFO, "{0} sent {1}", new Object[]{ctx.channel(), ((TextWebSocketFrame) msg).text()});
    }
    super.write(ctx, msg, promise);
  }
}

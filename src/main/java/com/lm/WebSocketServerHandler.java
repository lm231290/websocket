/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.lm;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;

import static com.lm.WebSocketServer.CLIENT_NAMES;
import static com.lm.WebSocketServer.PROPERTIES;
import static com.lm.WebSocketServer.SSL;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handles handshakes and messages
 */
public class WebSocketServerHandler extends SimpleChannelInboundHandler<Object> {

  private WebSocketServerHandshaker handshaker;

  @Override
  public void channelRead0(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof FullHttpRequest) {
      handleHttpRequest(ctx, (FullHttpRequest) msg);
    } else if (msg instanceof WebSocketFrame) {
      handleWebSocketFrame(ctx, (WebSocketFrame) msg);
    }
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
    // Handle a bad request.
    if (!req.getDecoderResult().isSuccess()) {
      sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
      return;
    }

    // Allow only GET methods.
    if (req.getMethod() != GET) {
      sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
      return;
    }

    // Send the demo page and favicon.ico
    if ("/".equals(req.getUri())) {
      ByteBuf content = WebSocketServerIndexPage.getContent(getWebSocketLocation(req));
      FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, OK, content);

      res.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");
      HttpHeaders.setContentLength(res, content.readableBytes());

      sendHttpResponse(ctx, req, res);
      return;
    }
    if (!req.getUri().startsWith(PROPERTIES.getProperty("websocket_path"))) {

      FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
      sendHttpResponse(ctx, req, res);
      return;
    }

    // Handshake
    WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
      getWebSocketLocation(req), null, false);
    handshaker = wsFactory.newHandshaker(req);
    if (handshaker == null) {
      WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
    } else {
      handshaker.handshake(ctx.channel(), req);
    }
  }

  private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {

    Channel channel = ctx.channel();
    // Check for closing frame
    if (frame instanceof CloseWebSocketFrame) {
      closeFrame(channel, (CloseWebSocketFrame) frame.retain());
      return;
    }
    if (frame instanceof PingWebSocketFrame) {
      channel.write(new PongWebSocketFrame(frame.content().retain()));
      return;
    }
    if (!(frame instanceof TextWebSocketFrame)) {
      throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
        .getName()));
    }

    String request = ((TextWebSocketFrame) frame).text();
    try {
      switch (getPartOfString(request, 1)) {
        case "bye":
          closeFrame(channel, new CloseWebSocketFrame());
          break;
        case "login":
          login(channel, request);
          break;
        case "plus":
          calculate(channel, request, new Operation() {
            @Override
            public long operate(int a, int b) {
              return a + b;
            }
          });
          break;
        case "minus":
          calculate(channel, request, new Operation() {
            @Override
            public long operate(int a, int b) {
              return a - b;
            }
          });
          break;
        case "multiply":
          calculate(channel, request, new Operation() {
            @Override
            public long operate(int a, int b) {
              return a * b;
            }
          });
          break;
        case "divide":
          calculate(channel, request, new Operation() {
            @Override
            public long operate(int a, int b) {
              return a / b;
            }
          });
          break;
        case "version":
          sendVersion(channel);
          break;
        default:
          throw new IncorrectFormatException();
      }
    } catch (IncorrectFormatException | NumberFormatException | ArithmeticException e) {
      channel.write(new TextWebSocketFrame("01:Неверный формат сообщения"));
    } catch (InternalException e) {
      channel.write(new TextWebSocketFrame("02:Ошибка на сервере"));
    }
  }

  private static void sendHttpResponse(
    ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
    // Generate an error page if response getStatus code is not OK (200).
    if (res.getStatus().code() != 200) {
      ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8);
      res.content().writeBytes(buf);
      buf.release();
      HttpHeaders.setContentLength(res, res.content().readableBytes());
    }

    // Send the response and close the connection if necessary.
    ChannelFuture f = ctx.channel().writeAndFlush(res);
    if (!HttpHeaders.isKeepAlive(req) || res.getStatus().code() != 200) {
      f.addListener(ChannelFutureListener.CLOSE);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }

  @Override
  public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
    CLIENT_NAMES.remove(ctx.channel());
    super.channelUnregistered(ctx);
  }

  private static String getWebSocketLocation(FullHttpRequest req) {
    String location = req.headers().get(HOST) + PROPERTIES.getProperty("websocket_path");
    if (SSL) {
      return "wss://" + location;
    } else {
      return "ws://" + location;
    }
  }

  private static String getPartOfString(String str, int index) {
    String result = "";
    int count = 0;
    for (int i = 0; i < str.length(); i++) {
      if (PROPERTIES.getProperty("delimiter").charAt(0) == str.charAt(i)) {
        count++;
        if (count == index) {
          break;
        }
      } else {
        if (count == index - 1) {
          result += str.charAt(i);
        }
      }
    }
    return result;
  }

  private void closeFrame(Channel channel, CloseWebSocketFrame frame) {
    handshaker.close(channel, frame);
  }

  private void login(Channel channel, String request) throws InternalException {
    String accessKey = PROPERTIES.getProperty("access_key");
    if (accessKey == null) {
      throw new InternalException();
    }
    if (!accessKey.equals(getPartOfString(request, 3))) {
      channel.write(new TextWebSocketFrame("11:Неверный ключ"));
      return;
    }

    if (CLIENT_NAMES.containsKey(channel)) {
      channel.write(new TextWebSocketFrame("14:Логин занят"));
      return;
    }

    String login = getPartOfString(request, 2);
    if (login.isEmpty()) {
      channel.write(new TextWebSocketFrame("13:Логин не может быть пустым"));
      return;
    }

    if (CLIENT_NAMES.containsValue(login)) {
      channel.write(new TextWebSocketFrame("14:Логин занят"));
      return;
    }

    CLIENT_NAMES.put(channel, login);
    channel.write(new TextWebSocketFrame("14:Логин занят"));
  }

  private void calculate(Channel channel, String request, Operation operation) {
    if (!CLIENT_NAMES.containsKey(channel)) {
      channel.write(new TextWebSocketFrame("21:Необходимо авторизоваться"));
      return;
    }
    int a = Integer.parseInt(getPartOfString(request, 2));
    int b = Integer.parseInt(getPartOfString(request, 3));
    channel.write(new TextWebSocketFrame("20:" + operation.operate(a, b)));
  }

  private void sendVersion(Channel channel) throws InternalException {
    String version = PROPERTIES.getProperty("version");
    if (version == null) {
      throw new InternalException();
    }
    channel.write(new TextWebSocketFrame("30:" + version));
  }


  private class IncorrectFormatException extends Exception {
  }

  private class InternalException extends Exception {
  }
}

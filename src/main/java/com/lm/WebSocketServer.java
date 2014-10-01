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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.internal.chmv8.ConcurrentHashMapV8;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.logging.LogManager;

public final class WebSocketServer {

  static final boolean SSL = System.getProperty("ssl") != null;
  static final Properties PROPERTIES = new Properties();
  static Map<Channel, String> CLIENT_NAMES = new ConcurrentHashMapV8<>();

  public static void main(String[] args) throws Exception {

    try (InputStream resourceAsStream =
           WebSocketServer.class.getResourceAsStream(
             "/project.properties"
           )) {
      PROPERTIES.load(resourceAsStream);
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (args.length > 0) {
      PROPERTIES.setProperty("port", args[0]);
    }

    if (args.length > 1) {
      PROPERTIES.setProperty("access_key", args[1]);
    }


    setLogConfig("file".equals(PROPERTIES.getProperty("log")));

    // Configure SSL.
    final SslContext sslCtx;
    if (SSL) {
      SelfSignedCertificate ssc = new SelfSignedCertificate();
      sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
    } else {
      sslCtx = null;
    }

    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
//        .handler(new LoggingHandler(LogLevel.INFO))
        .childHandler(new WebSocketServerInitializer(sslCtx));

      int port = Integer.parseInt(PROPERTIES.getProperty("port", "8080"));
      Channel ch = b.bind(port).sync().channel();

      Console con = System.console();
      if (con != null) {
        while (ch.isOpen()) {
          String s = System.console().readLine().toLowerCase();
          switch (s) {
            case "logtofile":
              setLogConfig(true);
              break;
            case "logtoconsole":
              setLogConfig(false);
              break;
            case "exit":
              for (Channel channel : CLIENT_NAMES.keySet()) {
                channel.writeAndFlush(new TextWebSocketFrame("03:Сервер завершает работу"));
              }
              ch.close();
              break;
          }
        }
      }
      ch.closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

  private static void setLogConfig(boolean logToFile) {
    try (InputStream resourceAsStream =
           WebSocketServer.class.getResourceAsStream(
             logToFile ? "/file-logging.properties" : "/console-logging.properties"
           )) {
      LogManager.getLogManager().readConfiguration(resourceAsStream);
      System.out.println("Logger configuration was set " + (logToFile ? "to file" : "to console"));
    } catch (IOException e) {
      System.err.println("Could not setup logger configuration: " + e.toString());
    }
  }
}

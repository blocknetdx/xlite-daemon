package io.cloudchains.app.net.api.http.server;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.cloudchains.app.net.CoinInstance;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ExceptionHandler extends ChannelDuplexHandler {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.FINER, cause.getMessage());
        writeErrorResponse(ctx);
    }

    private void writeErrorResponse(ChannelHandlerContext ctx) {
        JsonObject response = new JsonObject();

        JsonObject errorJSON = new JsonObject();
        errorJSON.addProperty("code", -1);
        errorJSON.addProperty("message", "Unexpected Error");

        response.add("result", JsonNull.INSTANCE);
        response.add("error", errorJSON);

        ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, responseContent);

        writeResponse(ctx, httpResponse);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        ctx.close();
    }

    private void writeResponse(ChannelHandlerContext ctx, FullHttpResponse httpResponse) {
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
        httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        httpResponse.headers().set(HttpHeaderNames.SERVER, CoinInstance.getVersionString());

        ctx.write(httpResponse);
    }
}

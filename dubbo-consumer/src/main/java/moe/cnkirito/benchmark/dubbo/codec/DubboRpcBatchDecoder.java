package moe.cnkirito.benchmark.dubbo.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import moe.cnkirito.benchmark.dubbo.common.Bytes;
import moe.cnkirito.benchmark.dubbo.model.DubboRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * This class mainly hack the {@link io.netty.handler.codec.ByteToMessageDecoder} to provide batch submission capability.
 * This can be used the same way as ByteToMessageDecoder except the case your following inbound handler may get a decoded msg,
 * which actually is an array list, then you can submit the list of msgs to an executor to process. For example
 * <pre>
 *   if (msg instanceof List) {
 *       processorManager.getDefaultExecutor().execute(new Runnable() {
 *           @Override
 *           public void run() {
 *               // batch submit to an executor
 *               for (Object m : (List<?>) msg) {
 *                   RpcCommandHandler.this.process(ctx, m);
 *               }
 *           }
 *       });
 *   } else {
 *       process(ctx, msg);
 *   }
 * </pre>
 * You can check the method {@link AbstractBatchDecoder#channelRead(ChannelHandlerContext, Object)} ()}
 *   to know the detail modification.
 */
public class DubboRpcBatchDecoder extends AbstractBatchDecoder{
    // header length.
    protected static final int HEADER_LENGTH = 16;

    protected static final byte FLAG_EVENT = (byte) 0x20;

    private static final Logger logger = LoggerFactory.getLogger(DubboRpcBatchDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {

        try {
            do {
                int savedReaderIndex = byteBuf.readerIndex();
                Object msg = null;
                try {
                    msg = decode2(byteBuf);
                } catch (Exception e) {
                    throw e;
                }
                if (msg == DubboRpcBatchDecoder.DecodeResult.NEED_MORE_INPUT) {
                    byteBuf.readerIndex(savedReaderIndex);
                    break;
                }

                list.add(msg);
            } while (byteBuf.isReadable());
        } finally {
            if (byteBuf.isReadable()) {
                byteBuf.discardReadBytes();
            }
        }


        //list.add(decode2(byteBuf));
    }

    enum DecodeResult {
        NEED_MORE_INPUT, SKIP_INPUT
    }

    /**
     * Demo为简单起见，直接从特定字节位开始读取了的返回值，demo未做：
     * 1. 请求头判断
     * 2. 返回值类型判断
     *
     * @param byteBuf
     * @return
     */
    private Object decode2(ByteBuf byteBuf) {

        int savedReaderIndex = byteBuf.readerIndex();
        int readable = byteBuf.readableBytes();

        if (readable < HEADER_LENGTH) {
            return DubboRpcBatchDecoder.DecodeResult.NEED_MORE_INPUT;
        }

        byte[] header = new byte[HEADER_LENGTH];
        byteBuf.readBytes(header);
        int len = Bytes.bytes2int(header,12);
        int tt = len + HEADER_LENGTH;
        if (readable < tt) {
            return DubboRpcBatchDecoder.DecodeResult.NEED_MORE_INPUT;
        }

        byteBuf.readerIndex(savedReaderIndex);
        byte[] data = new byte[tt];
        byteBuf.readBytes(data);



        // HEADER_LENGTH + 1，忽略header & Response value type的读取，直接读取实际Return value
        // dubbo返回的body中，前后各有一个换行，去掉
        byte[] subArray = Arrays.copyOfRange(data, HEADER_LENGTH + 2, data.length - 1);

        long requestId = Bytes.bytes2long(data, 4);
//        logger.info("consumer-agent发送dubbo请求编号:{}", requestId);
        DubboRpcResponse response = new DubboRpcResponse();
        response.setRequestId(requestId);
        response.setBytes(subArray);
        return response;
    }
}

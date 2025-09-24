package com.bastengao.serialport;

import android.serialport.SerialPort;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;


public class SerialPortWrapper {
    public final static String DataReceivedEvent = "dataReceived";

    private SerialPort serialPort;
    private EventSender sender;
    private String path;
    private OutputStream out;
    private InputStream in;
    private Thread readThread;
    private Remover remover;
    private CmdProtocolParser protocolParser; // 新增协议解析器

    private AtomicBoolean closed = new AtomicBoolean(false);

    // readBufferSize 现在作为内部 RingBuffer 的容量
    public SerialPortWrapper(final String path, final int readBufferSize, SerialPort serialPort, final EventSender sender, Remover remover) {
        this.path = path;
        this.serialPort = serialPort;
        this.sender = sender;
        this.remover = remover;
        this.out = this.serialPort.getOutputStream();
        this.in = this.serialPort.getInputStream();
        
        // 初始化协议解析器
        this.protocolParser = new CmdProtocolParser(new CmdProtocolParser.PacketListener() {
            @Override
            public void onPacketReceived(byte[] packet) {
                // 当解析器成功解析出一个完整数据包时，此回调被触发
                WritableMap event = Arguments.createMap();
                String hex = SerialPortApiModule.bytesToHex(packet, packet.length);
                event.putString("data", hex);
                event.putString("path", path);
                Log.i("serialport", "read complete packet, size: " + packet.length + ", hex: " + hex);
                sender.sendEvent(DataReceivedEvent, event);
            }
        }, readBufferSize * 2); // RingBuffer容量可以设置得比单次读取缓冲区更大

        this.readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // 这个buffer只是用于in.read()的临时存储
                byte[] buffer = new byte[readBufferSize];
                while (!closed.get()) {
                    try {
                        if (in == null) return;
                        
                        // 从输入流读取数据到临时buffer
                        int size = in.read(buffer);

                        if (size > 0) {
                            // 将读取到的数据块送入协议解析器进行处理
                            protocolParser.handleData(buffer, size);
                        }
                    } catch (IOException e) {
                        if (!closed.get()) {
                           Log.e("serialport", "Error reading data: " + e.getMessage());
                           e.printStackTrace();
                        }
                        return;
                    }
                }
            }
        });
        this.readThread.start();
    }

    public WritableMap toJS() {
        WritableMap js = Arguments.createMap();
        js.putString("path", path);
        return js;
    }

    public void write(byte[] buffer) throws IOException {
        this.out.write(buffer);
    }

    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        
        if (readThread != null) {
            readThread.interrupt();
        }
        
        try {
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        if (this.remover != null) {
            this.remover.remove();
        }
        Log.i("serialport", "close " + this.path);
    }
}

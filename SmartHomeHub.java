import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SmartHomeHub {

    private short hubAddress;
    private URL serverURL;
    private Queue<Payload> sendQueue = new ArrayDeque<>();
    private BigInteger serialCounter = BigInteger.valueOf(1);
    public static final short BROADCASTING_ADDRESS = 0x3FFF;

    static class Packet {
        byte length;
        byte[] payload;
        byte crc8;

        @Override
        public String toString() {
            return "Packet{" +
                    "length=" + length +
                    ", payload=" + Arrays.toString(payload) +
                    ", crc8=" + crc8 +
                    '}';
        }
    }

    static class Payload {
        short src; // 14-битный адрес отправителя
        short dst; // 14-битный адрес получателя, 0x3FFF - широковещательный адрес
        BigInteger serial; // порядковый номер пакета, нумерация с 1
        byte dev_type; // тип устройства, отправившего пакет
        byte cmd; // Команда
        Payload.CmdBody cmd_body; // Тело команды

        public static Payload create() {
            return new Payload();
        }

        public Payload setSrc(short src) {
            this.src = src;
            return this;
        }

        public Payload setDst(short dst) {
            this.dst = dst;
            return this;
        }

        public Payload setSerial(BigInteger serial) {
            this.serial = serial;
            return this;
        }

        public Payload setDev_type(byte dev_type) {
            this.dev_type = dev_type;
            return this;
        }

        public Payload setCmd(byte cmd) {
            this.cmd = cmd;
            return this;
        }

        public Payload setCmd_body(Payload.CmdBody cmd_body) {
            this.cmd_body = cmd_body;
            return this;
        }

        abstract static class CmdBody {
        }

        static class CmdBodyDevice extends CmdBody {
            String dev_name = "";

            byte[] dev_props = new byte[0];

            @Override
            public String toString() {
                return "CmdBodyDevice{" +
                        "dev_name='" + dev_name + '\'' +
                        ", dev_props=" + Arrays.toString(dev_props) +
                        '}';
            }
        }

        static class CmdBodyEnvSensorProps extends CmdBody {
            byte sensors;
            Trigger[] triggers;

            static class Trigger {
                byte op;
                BigInteger value;
                String name;
            }
        }

        static class CmdBodySensorStatus extends CmdBody {
            BigInteger[] values;
        }

        static class CmdBodyTimer extends CmdBody {
            BigInteger timestamp;

            @Override
            public String toString() {
                return "CmdBodyTimer{" +
                        "timestamp=" + timestamp +
                        '}';
            }
        }


        @Override
        public String toString() {
            return "Payload{" +
                    "src=" + src +
                    ", dst=" + dst +
                    ", serial=" + serial +
                    ", dev_type=" + dev_type +
                    ", cmd=" + cmd +
                    ", cmd_body=" + (cmd_body == null ? "null" : cmd_body.toString()) +
                    '}';
        }
    }

//    static class Device {
//        short address;
//        String name;
//
//        // Тип устройства
//        byte type;
//    }

    static abstract class Device {
        short address;
        String name;
        byte type;
        boolean status;
    }



    private enum DEVICE_TYPES_ENUM {
        SmartHub(0x01), // SmartHub
        EnvSensor(0x02), // EnvSensor
        Switch(0x03), // Switch
        Lamp(0x04), // Lamp
        Socket(0x05), // Socket
        Clock(0x06); // Clock

        private final int value;

        DEVICE_TYPES_ENUM(int value) {
            this.value = value;
        }

        public byte getValue() {
            return (byte) value;
        }
    }

    private enum COMMANDS_ENUM {
        WHOISHERE(0x01), // WHOISHERE
        IAMHERE(0x02), // IAMHERE
        GETSTATUS(0x03), // GETSTATUS
        STATUS(0x04), // STATUS
        SETSTATUS(0x05), // SETSTATUS
        TICK(0x06); // TICK

        private final int value;

        COMMANDS_ENUM(int value) {
            this.value = value;
        }

        public byte getValue() {
            return (byte) value;
        }
    }

    private SmartHomeHub(String url, String address) {
        try {
            serverURL = new URL(url);
        } catch (Exception e) {
            throw new RuntimeException();
        }
        hubAddress = Short.parseShort(address, 16);
    }

    private void sendNextRequest() {
        try {
            HttpURLConnection connection = (HttpURLConnection) serverURL.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);

            var payload = sendQueue.poll();
            if (payload == null) {
                return;
            }

            connection.getOutputStream().write(encodePacketToTransfer(payload));
            connection.getOutputStream().flush();

            if (connection.getResponseCode() == 204) {
                System.exit(0);
            }

            if (connection.getResponseCode() != 200) {
                System.exit(99);
            }

            var response = connection.getInputStream().readAllBytes();
            processResponse(response);

        } catch (Exception e) {
            System.exit(99);
        }
        System.exit(0);
    }

    private void addRequestToQueue(Payload payload) {
        sendQueue.add(payload);
        serialCounter = serialCounter.add(BigInteger.ONE);
    }

    private void sendWhoIsHere() {
        var commandBody = new Payload.CmdBodyDevice();
        commandBody.dev_name = "SmartHub";
        commandBody.dev_props = new byte[0];
        var payload = Payload.create()
                .setSrc(hubAddress)
                .setDst(BROADCASTING_ADDRESS)
                .setSerial(serialCounter)
                .setDev_type(DEVICE_TYPES_ENUM.SmartHub.getValue())
                .setCmd(COMMANDS_ENUM.WHOISHERE.getValue())
                .setCmd_body(commandBody);

        addRequestToQueue(payload);
    }

    private void processPayload(Payload payload) {

    }

    private void processIAMHERE(Payload payload) {
        // Добавляем устройство в список
//        var device = new Device();
//        device.address = payload.src;
//        device.name = ((Payload.CmdBodyDevice) payload.cmd_body).dev_name;
//
//
//        addRequestToQueue(response);
    }

    /**
     * ============================
     * Кодирование & декодирование
     * ============================
     */

    private void processResponse(byte[] response) {

        var decoder = Base64.getUrlDecoder();
//        byte[] responseBytes = decoder.decode(response);

        ByteBuffer buffer = ByteBuffer.wrap(decoder.decode(response));

        do {
            processPacket(buffer);
        } while (buffer.hasRemaining());
    }

    private void processPacket(ByteBuffer buffer) {
        int length = buffer.get();
        var packet = new Packet();
        packet.length = (byte) length;
        packet.payload = new byte[length];
        buffer.get(packet.payload);
        packet.crc8 = buffer.get();

        if (packet.crc8 == calculateCRC(packet)) {
            var payload = decodePayloadFromBytes(packet.payload, packet.length);
            System.out.println(payload);
        }
    }

    private Payload decodePayloadFromBytes(byte[] payloadBytes, byte length) {
        ByteBuffer buffer = ByteBuffer.wrap(payloadBytes);

        var payload = new Payload();
        payload.src = readULEB128(buffer).shortValue();
        payload.dst = readULEB128(buffer).shortValue();
        payload.serial = readULEB128(buffer);
        payload.dev_type = buffer.get();
        payload.cmd = buffer.get();


        switch (payload.cmd) {
            case 0x01: // WHOISHERE
                System.out.println("Decode: WHOISHERE");
                payload.cmd_body = new Payload.CmdBodyDevice();
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_name = decodeStringFromBytes(buffer);
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_props = new byte[length - 1 - ((Payload.CmdBodyDevice) payload.cmd_body).dev_name.length()];
                break;
            case 0x02: // IAMHERE
                System.out.println("Decode: IAMHERE");
                payload.cmd_body = new Payload.CmdBodyDevice();
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_name = decodeStringFromBytes(buffer);
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_props = new byte[length - 1 - ((Payload.CmdBodyDevice) payload.cmd_body).dev_name.length()];
                break;
            case 0x03: // GETSTATUS
                System.out.println("Decode: GETSTATUS");
                break;
            case 0x04: // STATUS
                System.out.println("Decode: STATUS");
                break;
            case 0x05: // SETSTATUS
                System.out.println("Decode: SETSTATUS");
//                payload.cmd_body = new Payload.CmdBodyDevice();
                break;
            case 0x06: // TICK
                System.out.println("Decode: TICK");
                Payload.CmdBodyTimer cmdBodyTimer = new Payload.CmdBodyTimer();
                cmdBodyTimer.timestamp = readULEB128(buffer);
                payload.cmd_body = cmdBodyTimer;
                break;
        }

        return payload;
    }


    private byte[] encodePacketToTransfer(Payload payload) {
        var packet = new Packet();
        packet.payload = encodePayloadToBytes(payload);
        packet.length = (byte) packet.payload.length;
        packet.crc8 = calculateCRC(packet);

        ByteBuffer buffer = ByteBuffer.allocate(packet.payload.length + 2);
        buffer.put(packet.length);
        buffer.put(packet.payload);
        buffer.put(packet.crc8);
        buffer.flip();

        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(buffer.array()).getBytes();
    }

    private byte[] encodePayloadToBytes(Payload payload) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        writeULEB128(buffer, BigInteger.valueOf(payload.src));
        writeULEB128(buffer, BigInteger.valueOf(payload.dst));
        writeULEB128(buffer, payload.serial);

        buffer.put(payload.dev_type);
        buffer.put(payload.cmd);

        encodeCmdBody(buffer, payload.cmd_body, payload.cmd);

        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);

        return result;
    }

    private void encodeCmdBody(ByteBuffer buffer, Payload.CmdBody cmdBody, byte cmd) {
        switch (cmd) {
            case 0x01: // WHOISHERE
                encodeStringToBytes(buffer, "SmartHub");
                break;
            case 0x02: // IAMHERE
                break;
            case 0x03: // GETSTATUS
                break;
            case 0x04: // STATUS
                break;
            case 0x05: // SETSTATUS
                break;
            case 0x06: // TICK
                break;
        }
    }

    private Payload.CmdBody decodeCmdBody(ByteBuffer buffer, byte cmd, int length) {
        switch (cmd) {
            case 0x01: // WHOISHERE
                var cmdBody = new Payload.CmdBodyDevice();
                cmdBody.dev_name = decodeStringFromBytes(buffer);
                cmdBody.dev_props = new byte[length - 1 - cmdBody.dev_name.length()];
                buffer.get(cmdBody.dev_props);
                return cmdBody;
            case 0x02: // IAMHERE
                return null;
            case 0x03: // GETSTATUS
                return null;
            case 0x04: // STATUS
                return null;
            case 0x05: // SETSTATUS
                return null;
            case 0x06: // TICK
                Payload.CmdBodyTimer cmdBodyTimer = new Payload.CmdBodyTimer();
                cmdBodyTimer.timestamp = readULEB128(buffer);
                return cmdBodyTimer;
        }
        return null;

    }

    private byte calculateCRC(Packet packet) {
        final byte generator = 0x1D; // Генераторный полином
        byte crc = 0; // Начальное значение

        // Считаем контрольную сумму
        for (int i = 0; i < packet.length; i++) {
            crc ^= packet.payload[i]; // XOR
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80) != 0) { // Если старший бит равен 1
                    crc = (byte) ((crc << 1) ^ generator); // Сдвигаем влево и XOR
                } else {
                    crc <<= 1; // Сдвигаем влево
                }
            }
        }
        return crc;
    }

    private void writeULEB128(ByteBuffer buffer, BigInteger value) {
        while (true) {
            var b = value.byteValue();
            value = value.shiftRight(7);
            if (value.equals(BigInteger.ZERO)) {
                buffer.put(b);
                break;
            } else {
                buffer.put((byte) (b | 0x80));
            }
        }
    }

    private BigInteger readULEB128(ByteBuffer buffer) {
        BigInteger result = BigInteger.ZERO;
        int shift = 0;
        while (true) {
            var b = buffer.get();
            result = result.or(BigInteger.valueOf(b & 0x7F).shiftLeft(shift));
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    private void encodeStringToBytes(ByteBuffer buffer, String value) {
        buffer.put((byte) value.length());
        buffer.put(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeStringFromBytes(ByteBuffer buffer) {
        var length = buffer.get();
        var bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }


    public void run() {
        // Добавляем в очередь запрос на получение списка устройств
        sendWhoIsHere();

        // Цикл обработки очереди запросов, 200 и 204 продолжаем работу, другие код 99
        while (true) {
            sendNextRequest();
        }

//        System.exit(0);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.exit(99);
        }

        var smartHub = new SmartHomeHub(args[0], args[1]);

        smartHub.run();
    }
}

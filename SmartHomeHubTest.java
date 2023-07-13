import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class SmartHomeHubTest {

    public static final short BROADCASTING_ADDRESS = 0x3FFF;
    private short hubAddress; // 14-битный адрес вашего устройства-хаба в сети в шестнадцатеричном виде
    private URL serverURL;

    // Смысла не имеет, но учитываем что максимально время работы -> бесконечность(2^64)
    private BigInteger packetCounter = BigInteger.valueOf(1);


    static class PacketResponse {
        byte length;
        byte[] payload;
        byte crc8;

        @Override
        public String toString() {
            return "PacketResponse{" +
                    "length=" + length +
                    ", payload=" + Arrays.toString(payload) +
                    ", crc8=" + crc8 +
                    '}';
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

    /**
     * Типы устройств
     * 0x01 - SmartHub — это устройство, которое моделирует ваша программа, оно единственное устройство этого типа в сети;
     * 0x02 - EnvSensor — датчик характеристик окружающей среды (температура, влажность, освещенность, загрязнение воздуха);
     * 0x03 - Switch — переключатель;
     * 0x04 - Lamp — лампа;
     * 0x05 - Socket — розетка;
     * 0x06 - Clock — часы, которые широковещательно рассылают сообщения TICK. Часы гарантрированно присутствуют в сети и только в одном экземпляре.
     */
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


    static class Payload {

        public Payload(short src, short dst, BigInteger serial, byte dev_type, byte cmd) {
            this.src = src;
            this.dst = dst;
            this.serial = serial;
            this.dev_type = dev_type;
            this.cmd = cmd;
        }

        public Payload(short src, short dst, BigInteger serial, byte dev_type, byte cmd, CmdBody cmd_body) {
            this.src = src;
            this.dst = dst;
            this.serial = serial;
            this.dev_type = dev_type;
            this.cmd = cmd;
            this.cmd_body = cmd_body;
        }

        short src; // 14-битный адрес отправителя
        short dst; // 14-битный адрес получателя, 0x3FFF - широковещательный адрес
        BigInteger serial; // порядковый номер пакета, нумерация с 1
        byte dev_type; // тип устройства, отправившего пакет
        byte cmd; // Команда
        CmdBody cmd_body; // Тело команды

        // CmdBody может быть разного типа, в зависимости от команды
        // Поэтому создадим абстрактный класс CmdBody, от которого будут наследоваться все возможные типы
        abstract static class CmdBody {
        }

        // Класс для команды 0x01
        static class CmdBody01 extends CmdBody {
            BigInteger timestamp;
        }

        static class CmdBodyDevice extends CmdBody {
            // string — строка. Первый байт строки хранит ее длину, затем идут символы строки, в строке допустимы символы (байты) с кодами 32-126 (включительно).

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

        static class CmdBodyTICK extends CmdBody {
            BigInteger timestamp;
        }

        @Override
        public String toString() {
            return "Payload{" +
                    "src=" + src +
                    ", dst=" + dst +
                    ", serial=" + serial +
                    ", dev_type=" + dev_type +
                    ", cmd=" + cmd +
                    ", cmd_body=" + cmd_body +
                    '}';
        }
    }

    static class Pair<K, V> {
        private K key;
        private V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }
    }

    /**
     * Конструктор класса SmartHomeHub
     *
     * @param url     URL сервера
     * @param address 14-битный адрес хаба
     */
    public SmartHomeHubTest(String url, String address) {
        try {
            serverURL = new URL(url);
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        hubAddress = Short.parseShort(address, 16);
    }

    /**
     * Метод для запуска хаба
     */
    public void run() {
        try {
            sendWhoIsHere();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }


    /**
     * Отправка команды WHOISHERE
     */
    public void sendWhoIsHere() {
        var cmdBody = new Payload.CmdBodyDevice();
        cmdBody.dev_name = "SmartHub";
        Payload payload = new Payload(
                hubAddress,
                BROADCASTING_ADDRESS,
                packetCounter,
                DEVICE_TYPES_ENUM.SmartHub.getValue(),
                COMMANDS_ENUM.WHOISHERE.getValue(),
                cmdBody);
        sendPacket(payload);
    }


    /**
     * Функция для отправки пакета
     *
     * @param payload Содержимое пакета
     */
    private void sendPacket(Payload payload) {
        try {
            HttpURLConnection connection = (HttpURLConnection) serverURL.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);

            System.out.println(payload);
            // Кодируем пакет
            byte[] encodedPacket = encodePacket(payload);


            System.out.println(base64Encode(encodedPacket));

            System.out.println("Encoded packet: " + Arrays.toString(base64Encode(encodedPacket).getBytes()));

            // Отправляем пакет в base64
            connection.getOutputStream().write(base64Encode(encodedPacket).getBytes());
            connection.getOutputStream().flush();


            // Читаем ответ сервера и считаем пакеты

            List<Payload> payloads = readPackets(connection.getInputStream());
            System.out.println(payloads);

//            PacketResponse response = decodePacket(connection.getInputStream().readAllBytes());
//
//
//            // Декодируем содержимое пакета
//            Payload responsePayload = decodePayload(response);
//
//            // Выводим содержимое пакета
//            System.out.println(responsePayload);


        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    /**
     * Метод чтения списка пакетов
     *
     * @param inputStream Входной поток
     * @return Список пакетов
     */
    private List<Payload> readPackets(InputStream inputStream) {
        List<Payload> payloads = new ArrayList<>();
        try {

            int offset = 0;

            // Поскольку пакеты могут быть разной длины, то читаем пакеты до тех пор, пока не закончится входной поток
            while (inputStream.available() > 0) {
                // Считываем данные из входного потока
                byte[] data = inputStream.readAllBytes();

                var packet = decodePacket(data);

                // Декодируем содержимое пакета
                Pair<Payload, Integer> responsePayload = decodePayload(packet, offset);

                offset = responsePayload.getValue();
                var payload = responsePayload.getKey();

                // Выводим содержимое пакета
                System.out.println(payload);

                payloads.add(payload);
            }


        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        return payloads;
    }

    private byte[] encodePacket(Payload payload) {
        byte[] encodedPayload = encodePayload(payload);

        var packet = new PacketResponse();
        packet.length = (byte) (encodedPayload.length);
        packet.payload = encodedPayload;
        packet.crc8 = calculateCRC(packet);
        // Кодируем в массив байт
        ByteBuffer buffer = ByteBuffer.allocate(packet.payload.length + 2);
//        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(packet.length);
        buffer.put(packet.payload);
        buffer.put(packet.crc8);

        buffer.flip();
        return buffer.array();
    }

//    private byte[] encodePayload(Payload payload) {
//    }

    /**
     * Функция для декодирования содержимого пакета
     *
     * @param packet Пакет для декодирования
     * @param offset Смещение в пакете
     * @return Пара пакет, смещение
     */
    private Pair<Payload, Integer> decodePayload(PacketResponse packet, int offset) {
        byte[] byteArray = packet.payload;
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Читаем uleb128 и преобразовываем в BigInteger, а затем в short
        var src = readULEB128(buffer).shortValue();
        var dst = readULEB128(buffer).shortValue();
        var serial = readULEB128(buffer);
        var dev_type = buffer.get();
        var cmd = buffer.get();

        Payload payload = new Payload(src, dst, serial, dev_type, cmd);

        // В зависимости от команды, декодируем тело команды
        payload.cmd_body = decodeCmdBody(buffer, cmd);

        return new Pair<>(payload, offset + buffer.position());
    }
//    decodePayload(PacketResponse packet, int offset) {

//    private Payload decodePayload(PacketResponse packet, int offset) {


//        byte[] byteArray = packet.payload;
//        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
//        buffer.order(ByteOrder.LITTLE_ENDIAN);
//
//        // Читаем uleb128 и преобразовываем в BigInteger, а затем в short
//        var src = readULEB128(buffer).shortValue();
//        var dst = readULEB128(buffer).shortValue();
//        var serial = readULEB128(buffer);
//        var dev_type = buffer.get();
//        var cmd = buffer.get();
//
//        Payload payload = new Payload(src, dst, serial, dev_type, cmd);
//
//        // В зависимости от команды, декодируем тело команды
//        payload.cmd_body = decodeCmdBody(buffer, cmd);
//
//        return payload;


    /**
     * Функция для кодирования содержимого пакета
     *
     * @param payload Содержимое пакета
     * @return Закодированное содержимое пакета
     */
    private byte[] encodePayload(Payload payload) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        buffer.order(ByteOrder.LITTLE_ENDIAN);
        writeULEB128(buffer, BigInteger.valueOf(payload.src));
        writeULEB128(buffer, BigInteger.valueOf(payload.dst));
        writeULEB128(buffer, payload.serial);

        buffer.put(payload.dev_type);
        buffer.put(payload.cmd);
        buffer = encodeCmdBody(buffer, payload.cmd_body, payload.cmd);

        // Убираем лишние байты без данных
        buffer.flip();
        byte[] byteArray = new byte[buffer.remaining()];
        buffer.get(byteArray);

        return byteArray;
    }


    /**
     * Функция для декодирования тела команды
     *
     * @param buffer Буфер для декодирования
     * @return Декодированное тело команды
     */
    private Payload.CmdBody decodeCmdBody(ByteBuffer buffer, byte cmd) {
        switch (cmd) {
            case 0x01:
                // Разбираем тело команды 0x01
                Payload.CmdBodyDevice cmdBodyDevice = new Payload.CmdBodyDevice();
                cmdBodyDevice.dev_name = decodeString(buffer);

                // Получаем байтовый массив из буфера с данными dev_props
                byte[] dev_props = new byte[buffer.remaining()];
                buffer.get(dev_props);
                return cmdBodyDevice;

            case 0x02:
                // Команда IAMHERE

            case 0x06:
                // Разбираем тело команды 0x06
                Payload.CmdBodyTICK cmdBodyTICK = new Payload.CmdBodyTICK();
                cmdBodyTICK.timestamp = readULEB128(buffer);
                System.out.println("Timestamp: " + cmdBodyTICK.timestamp);
                return cmdBodyTICK;
            default:
                System.out.println("Error: Unknown command");
                return null;
        }
    }

    /**
     * Функция для кодирования тела команды
     *
     * @param buffer  Буфер для кодирования
     * @param cmdBody Тело команды
     * @param cmd     Команда
     */
    private ByteBuffer encodeCmdBody(ByteBuffer buffer, Payload.CmdBody cmdBody, byte cmd) {
        switch (cmd) {
            case 0x01:
                // Кодируем тело команды 0x01
                Payload.CmdBodyDevice cmdBodyDevice = (Payload.CmdBodyDevice) cmdBody;
                // Кодируем тело команды 0x01 в буфер, 2 поля по dev_name: string, dev_props: bytes
                return encodeString(buffer, cmdBodyDevice.dev_name);
            case 0x06:
                break;

        }
        return buffer;
    }

    /**
     * Кодирование строки в буфер
     * <p>
     * Первый байт - длина строки
     * Остальные байты - содержимое строки
     * </p>
     *
     * @param buffer Буфер для кодирования
     * @param value  Строка для кодирования
     */
    private ByteBuffer encodeString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.put((byte) bytes.length);
        buffer.put(bytes);
        return buffer;
    }


    private String decodeString(ByteBuffer buffer) {
        BigInteger length = readULEB128(buffer);
        byte[] bytes = new byte[length.intValue()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private BigInteger readULEB128(ByteBuffer buffer) {
        BigInteger result = BigInteger.ZERO;
        int shift = 0;
        byte b;
        do {
            b = buffer.get();
            BigInteger value = BigInteger.valueOf(b & 0x7f);
            value = value.shiftLeft(shift);
            result = result.or(value);
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    private void writeULEB128(ByteBuffer buffer, BigInteger value) {
        while (value.compareTo(BigInteger.valueOf(0x80)) >= 0) {
            byte b = value.and(BigInteger.valueOf(0x7f)).byteValue();
            value = value.shiftRight(7);
            b |= 0x80;
            buffer.put(b);
        }

        buffer.put(value.byteValue());
    }

    /**
     * Декодирование из ULEB128 в int
     *
     * @param bytes Массив байт для декодирования
     * @return Декодированное число
     */
    private int decodeULEB128(byte[] bytes) {
        int result = 0;
        int shift = 0;
        for (int i = 0; i < bytes.length; i++) {
            result |= (bytes[i] & 0x7f) << shift;
            if ((bytes[i] & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    /**
     * Функция для декодирования пакета
     *
     * @param packet Пакет для декодирования
     * @return Декодированный пакет
     */
    private PacketResponse decodePacket(byte[] packet) {
        byte[] decodedBytes = base64Decode(packet);

        PacketResponse packetResponse = new PacketResponse();
        packetResponse.length = decodedBytes[0];
        packetResponse.payload = new byte[packetResponse.length];
        System.arraycopy(decodedBytes, 1, packetResponse.payload, 0, packetResponse.length);
        packetResponse.crc8 = decodedBytes[decodedBytes.length - 1];

        return packetResponse;
    }

    /**
     * Функция проверки контрольной суммы
     *
     * @param packet Пакет для проверки
     * @return Результат проверки
     */
    private boolean checkCRC(PacketResponse packet) {
        return calculateCRC(packet) == packet.crc8;
    }

    /**
     * Подсчёт контрольной суммы
     *
     * @param packet Пакет для подсчёта контрольной суммы
     * @return Контрольная сумма
     */
    private byte calculateCRC(PacketResponse packet) {
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

    /**
     * Функция для кодирования строки в base64
     *
     * @param str Строка для кодирования
     * @return Закодированная строка
     */
    private String base64Encode(String str) {
        var encoder = Base64.getUrlEncoder();
        return encoder.encodeToString(str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Функция для кодирования байтов в base64
     *
     * @param bytes Байты для кодирования
     * @return Закодированная строка в unpadded base64
     */
    private String base64Encode(byte[] bytes) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes);
    }

    /**
     * Функция для декодирования байтов из base64
     *
     * @param bytes Байты для декодирования
     * @return Строка
     */
    private byte[] base64Decode(byte[] bytes) {
        var decoder = Base64.getUrlDecoder();
        return decoder.decode(bytes);
    }


    /**
     * Точка входа в программу
     *
     * @param args Аргументы командной строки
     *             args[0] - URL сервера;
     *             args[1] - 14-битный адрес хаба;
     *             Пример: java SmartHomeHub http://localhost:9998 ef0
     */
    public static void main(String[] args) {
        // Проверяем, что переданы параметры
        if (args.length < 2) {
            return;
        }

        // Создаем объект класса SmartHomeHub
        SmartHomeHubTest hub = new SmartHomeHubTest(args[0], args[1]);

        // Запускаем хаб
        hub.run();
    }
}

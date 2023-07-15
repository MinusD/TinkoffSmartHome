import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SmartHomeHub {

    private final short hubAddress;
    private URL serverURL;
    private BigInteger whoIsHereTimestamp = BigInteger.valueOf(-1);
    private BigInteger currentTimestamp = BigInteger.valueOf(0);
    private Queue<Payload> sentQueue = new ArrayDeque<>();
    private Map<Short, BigInteger> waitingResponses = new HashMap<>();
    private Map<Short, Device> devices = new HashMap<>();
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

        public Payload setDevType(byte dev_type) {
            this.dev_type = dev_type;
            return this;
        }

        public Payload setCmd(byte cmd) {
            this.cmd = cmd;
            return this;
        }

        public Payload setCmdBody(Payload.CmdBody cmd_body) {
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

        static class CmdBodyStatus extends CmdBody {
            boolean status;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Payload payload)) return false;
            return src == payload.src &&
                    dst == payload.dst &&
                    dev_type == payload.dev_type &&
                    cmd == payload.cmd &&
                    Objects.equals(serial, payload.serial) &&
                    Objects.equals(cmd_body, payload.cmd_body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(src, dst, serial, dev_type, cmd, cmd_body);
        }
    }

    abstract class Device {
        short address;
        String name;
        boolean updated = false;
        abstract DEVICE_TYPES_ENUM getType();
        abstract void setData(ByteBuffer buffer);
    }

    class Lamp extends Device {
        boolean status;

        @Override
        void setData(ByteBuffer buffer) {
            status = buffer.get() == 1;
        }

        @Override
        DEVICE_TYPES_ENUM getType() {
            return DEVICE_TYPES_ENUM.Lamp;
        }

        @Override
        public String toString() {
            return "Lamp{" +
                    "status=" + status +
                    ", address=" + address +
                    ", name='" + name + '\'' +
                    '}';
        }
    }

    class Switch extends Device {
        boolean status;

        @Override
        void setData(ByteBuffer buffer) {
            updated = true;
            status = buffer.get() == 1;
        }

        List<String> devicesNames = new ArrayList<>();

        @Override
        DEVICE_TYPES_ENUM getType() {
            return DEVICE_TYPES_ENUM.Switch;
        }

        @Override
        public String toString() {
            return "Switch{" +
                    "status=" + status +
                    ", address=" + address +
                    ", name='" + name + '\'' +
                    ", devicesNames=" + devicesNames +
                    '}';
        }
    }

    class Socket extends Device {
        boolean status;

        @Override
        void setData(ByteBuffer buffer) {
            status = buffer.get() == 1;
        }

        @Override
        DEVICE_TYPES_ENUM getType() {
            return DEVICE_TYPES_ENUM.Socket;
        }

        @Override
        public String toString() {
            return "Socket{" +
                    "status=" + status +
                    ", name='" + name + '\'' +
                    ", address=" + address +
                    '}';
        }
    }

    class EnvSensor extends Device {
        public EnvSensor(Short address, String name, byte sensors, byte[] triggersBytes) {
            this.address = address;
            this.name = name;
            // Определяем какие сенсоры есть
            temperature = ((sensors & 0x1) == 0x1) ? 0 : -1;
            humidity = ((sensors & 0x2) == 0x2) ? 0 : -1;
            illumination = ((sensors & 0x4) == 0x4) ? 0 : -1;
            airPollution = ((sensors & 0x8) == 0x8) ? 0 : -1;

            // Определяем триггеры
            if (triggersBytes.length > 0) {
                var buffer = ByteBuffer.wrap(triggersBytes);
                var triggersCount = buffer.get();
                for (int i = 0; i < triggersCount; i++) {
                    var trigger = new Trigger();
                    var op = buffer.get();

                    // Младший бит - включить или выключить устройство
                    trigger.enabled = (op & 0x01) == 0x01;
                    // Следующий бит - операция сравнения
                    trigger.more = (op & 0x02) == 0x02;
                    // Следующие 2 бита - тип сенсора
                    trigger.sensorType = (byte) ((op & 0x0C) >> 2);
                    // Значение триггера
                    trigger.value = readULEB128(buffer).intValue();
                    // Имя триггера
                    trigger.name = decodeStringFromBytes(buffer);
                    triggers.add(trigger);
                }
            }
        }

        // Температура
        int temperature;
        // Влажность
        int humidity;
        // Освещенность
        int illumination;
        // Загрязненность воздуха (PM2.5)
        int airPollution;

        // Триггеры
        List<Trigger> triggers = new ArrayList<>();

        @Override
        DEVICE_TYPES_ENUM getType() {
            return DEVICE_TYPES_ENUM.EnvSensor;
        }

        @Override
        void setData(ByteBuffer buffer) {
            updated = true;
            var size = buffer.get();
            if (size == 0) return;
            // Если сенсора нет, то значение равно -1
            if (temperature != -1) {
                temperature = readULEB128(buffer).intValue();
            }
            if (humidity != -1) {
                humidity = readULEB128(buffer).intValue();
            }
            if (illumination != -1) {
                illumination = readULEB128(buffer).intValue();
            }
            if (airPollution != -1) {
                airPollution = readULEB128(buffer).intValue();
            }
        }

        class Trigger {
            // Включить или выключить устройство
            boolean enabled;

            // Операция сравнения
            boolean more; // true - когда значение датчика больше значения триггера, false - когда меньше

            // Тип сенсора
            byte sensorType;
            int value;
            String name;

            @Override
            public String toString() {
                return "Trigger{" +
                        "enabled=" + enabled +
                        ", more=" + more +
                        ", sensorType=" + sensorType +
                        ", value=" + value +
                        ", name='" + name + '\'' +
                        '}';
            }

            boolean check() {
                return switch (sensorType) {
                    case 0 -> temperature > value;
                    case 1 -> humidity > value;
                    case 2 -> illumination > value;
                    case 3 -> airPollution > value;
                    default -> false;
                };
            }
        }

        @Override
        public String toString() {
            return "EnvSensor{" +
                    "temperature=" + temperature +
                    ", humidity=" + humidity +
                    ", illumination=" + illumination +
                    ", airPollution=" + airPollution +
                    ", triggers=" + triggers +
                    ", address=" + address +
                    ", name='" + name + '\'' +
                    '}';
        }
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

    /**
     * Конструктор
     *
     * @param url     URL сервера
     * @param address адрес хаба
     */
    private SmartHomeHub(String url, String address) {
        try {
            serverURL = new URL(url);
        } catch (Exception ignored) {
            serverURL = null;
        }
        hubAddress = Short.parseShort(address, 16);
    }

    /**
     * Получить устройство по имени
     *
     * @param name имя устройства
     * @return устройство
     */
    private Device getDeviceByName(String name) {
        return devices.values().stream().filter(device -> device.name.equals(name)).findFirst().orElse(null);
    }

    /**
     * Удалить устройство по имени
     *
     * @param name имя устройства
     */
    private void deleteDeviceByName(String name) {
        for (var device : devices.values()) {
            if (device.name.equals(name)) {
                devices.remove(device.address);
                break;
            }
        }
    }

    /**
     * Удалить устройство по адресу
     *
     * @param address адрес устройства
     */
    private void deleteDeviceByAddress(short address) {
        devices.remove(address);
    }

    /**
     * Получить интервал между двумя числами-временными метками
     *
     * @param first  первое число
     * @param second второе число
     * @return интервал
     */
    private int getInterval(BigInteger first, BigInteger second) {
        return second.subtract(first).intValue();
    }

    /**
     * Отправка следующего запроса (пакетов)
     */
    private void sentNextRequest() {
        try {
            HttpURLConnection connection = (HttpURLConnection) serverURL.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);

            // Забираем из очереди все пакеты, которые нужно отправить
            var payloads = new ArrayList<Payload>();
            while (sentQueue.size() > 0) {
                var payload = sentQueue.poll();
                if (payload.cmd != COMMANDS_ENUM.IAMHERE.getValue() && payload.cmd != COMMANDS_ENUM.WHOISHERE.getValue()) {
                    waitingResponses.put(payload.dst, currentTimestamp);
                }
                payloads.add(payload);
            }

            connection.getOutputStream().write(encodePacketsToTransfer(payloads));
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
    }

    /**
     * Добавить запрос в очередь
     *
     * @param payload тело запроса
     */
    private void addRequestToQueue(Payload payload) {
        sentQueue.add(payload);
        serialCounter = serialCounter.add(BigInteger.ONE);
    }

    /**
     * Подготовка пакета WHOISHERE
     */
    private void sentWhoIsHere() {
        var commandBody = new Payload.CmdBodyDevice();
        commandBody.dev_name = "SmartHub";
        commandBody.dev_props = new byte[0];
        var payload = Payload.create()
                .setSrc(hubAddress)
                .setDst(BROADCASTING_ADDRESS)
                .setSerial(serialCounter)
                .setDevType(DEVICE_TYPES_ENUM.SmartHub.getValue())
                .setCmd(COMMANDS_ENUM.WHOISHERE.getValue())
                .setCmdBody(commandBody);

        addRequestToQueue(payload);
    }

    /**
     * Подготовка пакета GETSTATUS
     *
     * @param device устройство
     */
    private void sentGetStatus(Device device) {
        var payload = Payload.create()
                .setSrc(hubAddress)
                .setDst(device.address)
                .setSerial(serialCounter)
                .setDevType(device.getType().getValue())
                .setCmd(COMMANDS_ENUM.GETSTATUS.getValue());
        addRequestToQueue(payload);
    }

    /**
     * Подготовка пакета SETSTATUS
     *
     * @param device устройство
     * @param status статус
     */
    private void sentSetStatus(Device device, Payload.CmdBody status) {
        var payload = Payload.create()
                .setSrc(hubAddress)
                .setDst(device.address)
                .setSerial(serialCounter)
                .setDevType(device.getType().getValue())
                .setCmd(COMMANDS_ENUM.SETSTATUS.getValue())
                .setCmdBody(status);
        addRequestToQueue(payload);
    }

    /**
     * Управление устройством
     *
     * @param name   имя устройства
     * @param status статус
     */
    private void manageDevice(String name, Boolean status) {
        var device = getDeviceByName(name);
        if (device == null) {
            return;
        }
        // Проверяем тип устройства
        if (device.getType() != DEVICE_TYPES_ENUM.Lamp && device.getType() != DEVICE_TYPES_ENUM.Socket) {
            return;
        }

        if (device instanceof Lamp) {
            if (((Lamp) device).status == status) {
                return;
            }
        } else if (device instanceof Socket) {
            if (((Socket) device).status == status) {
                return;
            }
        }

        var commandBody = new Payload.CmdBodyStatus();
        commandBody.status = status;
        sentSetStatus(device, commandBody);
    }

    /**
     * Обработка пакета WHOISHERE
     *
     * @param payload тело пакета
     */
    private void processWhoIsHere(Payload payload) {
        // Проверяем, было ли устройство уже добавлено в список (По имени)
        var device = getDeviceByName(((Payload.CmdBodyDevice) payload.cmd_body).dev_name);
        if (device != null) {
            // Удаляем устройство из списка
            deleteDeviceByName(device.name);
        }
        // Добавляем устройство в список
        var newDevice = decodeDeviceFromBytes(payload);
        if (newDevice != null) {
            devices.put(newDevice.address, newDevice);
            sentGetStatus(newDevice);
        }
    }

    /**
     * Обработка пакета IAMHERE
     *
     * @param payload тело пакета
     */
    private void processIAmHere(Payload payload) {
        // Добавляем устройство в список
        var device = decodeDeviceFromBytes(payload);

        if (device == null) {
            return;
        }
        // Проверяем, успело ли устройство ответить за 300мс
        if (getInterval(whoIsHereTimestamp, currentTimestamp) > 300) {
            return;
        }

        devices.put(device.address, device);

        // Отправляем запрос на получение статуса устройства
        sentGetStatus(device);
    }

    /**
     * Обработка пакета STATUS
     *
     * @param payload тело пакета
     * @param buffer  буфер с данными
     */
    private void processStatus(Payload payload, ByteBuffer buffer) {
        // Есть ли ожидание ответа от устройства по адресу
        var time = waitingResponses.get(payload.src);
        // Если есть
        if (time != null) {
            // Проверяем, успело ли устройство ответить за 300мс
            if (getInterval(time, currentTimestamp) > 300) {
                // Удаляем устройство из списка, если не успело ответить
                deleteDeviceByAddress(payload.src);
                waitingResponses.remove(payload.src);
                return;
            } else {
                // Удаляем устройство из списка ожидания ответа
                waitingResponses.remove(payload.src);
            }
        }

        // Обновляем данные устройства если оно успело ответить или самостоятельно отправило данные
        var device = devices.get(payload.src);
        if (device != null) {
            device.setData(buffer);
        }
    }

    /**
     * Обработка пакета TICK
     *
     * @param payload тело пакета
     */
    private void processTICK(Payload payload) {
        currentTimestamp = ((Payload.CmdBodyTimer) payload.cmd_body).timestamp;
        if (whoIsHereTimestamp.intValue() == -1) {
            whoIsHereTimestamp = currentTimestamp;
        }
    }

    /**
     * Обработка обновления устройств
     */
    private void processUpdateDevices() {
        for (var device : devices.values()) {
            if (device.updated) {
                switch (device.getType()) {
                    case Switch -> {
                        var switchDevice = (Switch) device;
                        switchDevice.devicesNames.forEach((name) -> manageDevice(name, switchDevice.status));
                    }
                    case EnvSensor -> {
                        var envSensor = (EnvSensor) device;
                        // Проверяем все триггеры
                        envSensor.triggers.forEach((trigger) -> {
                            if (trigger.check()) {
                                manageDevice(trigger.name, trigger.enabled);
                            }
                        });
                    }
                }
                device.updated = false;
            }
        }
    }

    /**
     * Обработка ответа от сервера
     *
     * @param response ответ от сервера
     */
    private void processResponse(byte[] response) {
        var decoder = Base64.getUrlDecoder();
        ByteBuffer buffer = ByteBuffer.wrap(decoder.decode(response));

        while (buffer.hasRemaining()) {
            decodePacketFromBytes(buffer);
        }
        // Обновляем устройства
        processUpdateDevices();
        // Проверяем выключенные устройства
        processDisabledDevices();
    }

    /**
     * Проверка выключенных устройств
     */
    private void processDisabledDevices(){
        // Проверяем все запросы
        for (var request : waitingResponses.entrySet()) {
            // Если запрос не был обработан за 300мс
            if (getInterval(request.getValue(), currentTimestamp) > 300) {
                // Удаляем устройство из списка, если не успело ответить
                deleteDeviceByAddress(request.getKey());
                waitingResponses.remove(request.getKey());
            }
        }
    }

    /**
     * ============================
     * Кодирование & декодирование
     * ============================
     */

    /**
     * Декодирование пакета из байтов
     *
     * @param buffer буфер с данными
     */
    private void decodePacketFromBytes(ByteBuffer buffer) {
        var packet = new Packet();
        int length = buffer.get();
        packet.length = (byte) length;
        packet.payload = new byte[length];
        buffer.get(packet.payload);
        packet.crc8 = buffer.get();

        if (packet.crc8 == calculateCRC(packet)) {
            decodePayloadFromBytes(packet.payload);
        }
    }

    /**
     * Декодирование устройства из байтов
     *
     * @param payload тело пакета
     * @return устройство
     */
    private Device decodeDeviceFromBytes(Payload payload) {
        var name = ((Payload.CmdBodyDevice) payload.cmd_body).dev_name;
        var buffer = ByteBuffer.wrap(((Payload.CmdBodyDevice) payload.cmd_body).dev_props);
        switch (payload.dev_type) {
            case 0x02 -> {
                byte sensors = buffer.get();
                byte[] triggers = new byte[buffer.remaining()];
                buffer.get(triggers);
                return new EnvSensor(
                        payload.src,
                        ((Payload.CmdBodyDevice) payload.cmd_body).dev_name,
                        sensors,
                        triggers
                );
            }
            case 0x03 -> {
                var switchDevice = new Switch();
                switchDevice.address = payload.src;
                switchDevice.name = name;
                var arrayLength = buffer.get();
                for (int i = 0; i < arrayLength; i++) {
                    switchDevice.devicesNames.add(decodeStringFromBytes(buffer));
                }
                return switchDevice;
            }
            case 0x04 -> {
                var lamp = new Lamp();
                lamp.address = payload.src;
                lamp.name = name;
                return lamp;
            }
            case 0x05 -> {
                var socket = new Socket();
                socket.address = payload.src;
                socket.name = name;
                return socket;
            }
        }
        return null;
    }

    /**
     * Декодирование тела пакета из байтов
     *
     * @param payloadBytes тело пакета
     */
    private void decodePayloadFromBytes(byte[] payloadBytes) {
        ByteBuffer buffer = ByteBuffer.wrap(payloadBytes);
        var payload = Payload.create()
                .setSrc(readULEB128(buffer).shortValue())
                .setDst(readULEB128(buffer).shortValue())
                .setSerial(readULEB128(buffer))
                .setDevType(buffer.get())
                .setCmd(buffer.get());

        if (payload.dst != hubAddress && payload.dst != BROADCASTING_ADDRESS) {
            return;
        }

        switch (payload.cmd) {
            case 0x01 -> { // WHOISHERE
                payload.cmd_body = new Payload.CmdBodyDevice();
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_name = decodeStringFromBytes(buffer);
                byte[] dev_props = new byte[buffer.remaining()];
                buffer.get(dev_props);
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_props = dev_props;
                processWhoIsHere(payload);
            }
            case 0x02 -> { // IAMHERE
                payload.cmd_body = new Payload.CmdBodyDevice();
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_name = decodeStringFromBytes(buffer);
                byte[] dev_props = new byte[buffer.remaining()];
                buffer.get(dev_props);
                ((Payload.CmdBodyDevice) payload.cmd_body).dev_props = dev_props;
                processIAmHere(payload);
            }
            case 0x04 -> // STATUS
                    processStatus(payload, buffer);
            case 0x06 -> { // TICK
                Payload.CmdBodyTimer cmdBodyTimer = new Payload.CmdBodyTimer();
                cmdBodyTimer.timestamp = readULEB128(buffer);
                payload.cmd_body = cmdBodyTimer;
                processTICK(payload);
            }
        }
    }

    /**
     * Кодирование пакета в байты
     *
     * @param payloads тела пакетов
     * @return байты пакетов
     */
    private byte[] encodePacketsToTransfer(List<Payload> payloads) {
        var buffer = ByteBuffer.allocate(2048);

        payloads.forEach((p) -> {
            var packet = new Packet();
            packet.payload = encodePayloadToBytes(p);
            packet.length = (byte) packet.payload.length;
            packet.crc8 = calculateCRC(packet);

            buffer.put(packet.length);
            buffer.put(packet.payload);
            buffer.put(packet.crc8);
        });

        // Убираем лишние байты
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);

        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(result).getBytes();
    }

    /**
     * Кодирование тела пакета в байты
     *
     * @param payload тело пакета
     * @return байты тела пакета
     */
    private byte[] encodePayloadToBytes(Payload payload) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        writeULEB128(buffer, BigInteger.valueOf(payload.src));
        writeULEB128(buffer, BigInteger.valueOf(payload.dst));
        writeULEB128(buffer, payload.serial);

        buffer.put(payload.dev_type);
        buffer.put(payload.cmd);

        encodeCmdBody(buffer, payload.cmd, payload.cmd_body);

        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);

        return result;
    }

    /**
     * Кодирование тела команды в байты
     *
     * @param buffer  буфер
     * @param cmd     команда
     * @param cmdBody тело команды
     */
    private void encodeCmdBody(ByteBuffer buffer, byte cmd, Payload.CmdBody cmdBody) {
        switch (cmd) {
            case 0x01, 0x02 -> // WHOISHERE
                    encodeStringToBytes(buffer, "SmartHub");
            case 0x05 -> { // SETSTATUS
                var cmdBodyStatus = (Payload.CmdBodyStatus) cmdBody;
                buffer.put((byte) (cmdBodyStatus.status ? 1 : 0));
            }
        }
    }

    /**
     * Вычисление контрольной суммы
     *
     * @param packet пакет
     * @return контрольная сумма
     */
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

    /**
     * Запись числа в формате ULEB128
     *
     * @param buffer буфер
     * @param value  число
     */
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

    /**
     * Чтение числа в формате ULEB128
     *
     * @param buffer буфер
     * @return число
     */
    private static BigInteger readULEB128(ByteBuffer buffer) {
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

    /**
     * Кодирование строки в байты
     *
     * @param buffer буфер
     * @param value  строка
     */
    private void encodeStringToBytes(ByteBuffer buffer, String value) {
        buffer.put((byte) value.length());
        buffer.put(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Декодирование строки из байтов
     *
     * @param buffer буфер
     * @return строка
     */
    private static String decodeStringFromBytes(ByteBuffer buffer) {
        var length = buffer.get();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Запуск хаба
     */
    public void run() {
        sentWhoIsHere();
        while (true) {
            sentNextRequest();
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.exit(99);
        }

        var smartHub = new SmartHomeHub(args[0], args[1]);

        smartHub.run();
    }
}

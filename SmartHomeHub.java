import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static java.lang.Integer.parseInt;

public class SmartHomeHub {

    // Переменная для хранения адреса хаба
    private int hubAddress;
    // Переменная для хранения URL сервера
    private URL serverURL;

//    static class Packet {
//        byte length;
//        Payload payload;
//        byte crc8;
//    }

//    static class Payload {
//        short src;
//        short dst;
//        int serial;
//        byte dev_type;
//        byte cmd;
//        CmdBody cmd_body;
//    }

//    static class CmdBody {
//        long timestamp;
//    }

    static class PacketResponse {
        byte length;
        byte[] payload;
        byte crc8;
    }


    /**
     * Конструктор класса SmartHomeHub
     *
     * @param url     URL сервера
     * @param address 14-битный адрес хаба
     */
    public SmartHomeHub(String url, int address) {
        try {
            serverURL = new URL(url);
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        // Сохраняем адрес хаба
        hubAddress = address;
    }

    /**
     * Метод для запуска хаба
     */
    public void run() {
        try {
            HttpURLConnection connection = (HttpURLConnection) serverURL.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            PacketResponse packet = decodePacket(connection.getInputStream().readAllBytes());

            System.out.println("Packet length: " + packet.length);
            System.out.println("Packet payload: " + new String(packet.payload));
            System.out.println("Packet CRC8: " + packet.crc8);
            // Проверяем контрольную сумму
            if (!checkCRC(packet)) {
                System.out.println("Error: CRC8 check failed");
                return;
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    /**
     * Функция для декодирования пакета
     *
     * @param packet Пакет для декодирования
     * @return Декодированный пакет
     */
    private PacketResponse decodePacket(byte[] packet) {
        PacketResponse response = new PacketResponse();
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
        return crc == packet.crc8;
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
     * Функция для декодирования строки из base64
     *
     * @param str Строка для декодирования
     * @return Декодированная строка
     */
    private byte[] base64Decode(String str) {
        var decoder = Base64.getUrlDecoder();
        return decoder.decode(str);
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
     * Функция для отправки пакета на сервер
     * Тестовая функцуия, пакет захардкожен в виде строки DbMG_38BBgaI0Kv6kzGK
     */
    private void sendPacket() {
        try {
            HttpURLConnection connection = (HttpURLConnection) serverURL.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);

            String base64Data = "DbMG_38BBgaI0Kv6kzGK";
            // Преобразуем строку в байты без кодирования
            byte[] decodedBytes = base64Data.getBytes(StandardCharsets.UTF_8);

            connection.getOutputStream().write(decodedBytes);
            connection.getOutputStream().flush();
            connection.getOutputStream().close();

            System.out.println("Response code: " + connection.getResponseCode());
            System.out.println("Response message: " + connection.getResponseMessage());

            byte[] response = connection.getInputStream().readAllBytes();
            System.out.println("Response: " + response);
            System.out.println("Response: " + base64Decode(response));

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
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

        // Парсим адрес хаба
        int hubAddress = parseInt(args[1], 16);

        // Создаем объект класса SmartHomeHub
        SmartHomeHub hub = new SmartHomeHub(args[0], hubAddress);

        // Запускаем хаб
        hub.run();
    }
}

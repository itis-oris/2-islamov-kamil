package server;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigLoader {
    public static String getServerIp() {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            return prop.getProperty("server.ip", "127.0.0.1"); // 127.0.0.1 - значение по умолчанию
        } catch (IOException e) {
            System.err.println("Файл настроек не найден, использую localhost");
            return "127.0.0.1";
        }
    }
}
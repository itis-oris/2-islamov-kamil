package app;

//этот класс был создан для того чтобы если игра перекидывалась другому игроку (например через FAT-jar)
//то у него все работало точно также и по умолчанию всегда запускался этот лаунчер
public class Launcher {
    public static void main(String[] args) {
        GameClient.main(args);
    }
}

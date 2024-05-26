package ipiad.crawler;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import ipiad.crawler.services.MainPageLinkExtractor;
import ipiad.crawler.services.NewsPageParser;
import ipiad.crawler.services.PageLoader;
import ipiad.crawler.utils.ElasticBridge;
import ipiad.crawler.utils.RequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;


public class Main {
    private static final String url = "https://ura.news/msk";
    private static final String INDEX_NAME = "news";
    private static final String EL_URL = "http://localhost:9200";
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) throws InterruptedException, IOException, TimeoutException {
        logger.info("Start service");
        ConnectionFactory rmqConFactory = new ConnectionFactory();
        rmqConFactory.setHost("127.0.0.1");
        rmqConFactory.setPort(5672);
        rmqConFactory.setVirtualHost("/");
        rmqConFactory.setUsername("rabbitmq");
        rmqConFactory.setPassword("rabbitmq");

        Connection connection = rmqConFactory.newConnection();
        Channel channel = connection.createChannel();
        channel.queueDeclare(RequestUtils.QUEUE_LINK, false, false, false, null);
        channel.queueDeclare(RequestUtils.QUEUE_PAGE, false, false, false, null);
        channel.close();
        connection.close();

        ElasticBridge elasticBridge = new ElasticBridge(EL_URL, INDEX_NAME);
        elasticBridge.createIndexIfNotExists();

        // запускаем парсинг главной страницы и вычленение из нее ссылок
        MainPageLinkExtractor mainPageLinkExtractor = new MainPageLinkExtractor(url, rmqConFactory);
        mainPageLinkExtractor.start();

        // запускаем скачивание и парсинг полученных новостных страниц
        NewsPageParser newsPageParser = new NewsPageParser(rmqConFactory, elasticBridge);
        newsPageParser.start();

        // запускаем получение распарсенных новостных моделей и сохраняем их в бд
        PageLoader pageLoader = new PageLoader(rmqConFactory, elasticBridge);
        pageLoader.start();

        // дожидаемся пока все потоки не закончат выполнение
        mainPageLinkExtractor.join();
        newsPageParser.join();
        pageLoader.join();
    }

}
package net.javasauce.ss.util;

import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.DownloadTasks;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/**
 * Created by covers1624 on 7/20/25.
 */
public class ToolUtils {

    public static String findLatest(HttpEngine http, String maven, MavenNotation notation) {
        if (!maven.endsWith("/")) maven += "/";

        var download = DownloadTasks.inMemoryDownload(http, maven + notation.toModulePath() + "maven-metadata.xml", null);

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document doc = builder.parse(new InputSource(new StringReader(download.toString())));
            doc.getDocumentElement().normalize();

            return doc.getElementsByTagName("latest")
                    .item(0)
                    .getTextContent();
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new RuntimeException("Failed to parse xml.", ex);
        }
    }
}

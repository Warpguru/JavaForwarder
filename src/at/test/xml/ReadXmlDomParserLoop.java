package at.test.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class ReadXmlDomParserLoop {

	private final static String xml = "Header line<p/>" + "<ul>" + "<li>First list entry with <b>bold</b> text</li>"
			+ "<li>Second list entry with <red>red</red> text</li>"
			+ "<li>Third list entry with <b><red>red bold</red></b> text</li>" + "</ul>" + "Footer line<p/>";

	private final static String CRLF = "\r\n";

	public static void main(String[] args) {
		StringBuffer sb = new StringBuffer();
		sb.append("<HTML>").append(CRLF);
		sb.append("<BODY>").append(CRLF);
		// Instantiate the Factory
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		byte[] bytes = ("<text>" + xml + "</text>").getBytes(Charset.forName("UTF-8"));
		try (InputStream is = new ByteArrayInputStream(bytes)) {
			// parse XML file
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(is);
			System.out.println("Root Element :" + doc.getDocumentElement().getNodeName());
			System.out.println("------");
			if (doc.hasChildNodes()) {
				printNode(doc.getChildNodes(), sb, 1);
			}
			sb.append("</BODY>").append(CRLF);
			sb.append("</HTML>").append(CRLF);
			System.out.println("------");
			System.out.println(sb.toString());
		} catch (ParserConfigurationException | SAXException | IOException e) {
			e.printStackTrace();
		}
	}

	private static void printNode(final NodeList nodeList, final StringBuffer sb, final int index) {
		String indent = "";
		if (index > 0) {
			indent = String.format("%" + index * 2 + "s", "");
		}
		for (int count = 0; count < nodeList.getLength(); count++) {
			Node tempNode = nodeList.item(count);
			// get node name and value
			System.out.println(String.format("\n%sNode #" + count, indent));
			System.out.println(String.format("%sNode Name =" + tempNode.getNodeName() + " [OPEN]", indent));
			System.out.println(String.format("%sNode Value =" + tempNode.getTextContent(), indent));
			if (tempNode.getNodeType() == Node.TEXT_NODE) {
				System.out.println(String.format("%s[" + tempNode.getNodeValue() + "]", indent));
				sb.append(tempNode.getNodeValue()).append(CRLF);
			} else if (tempNode.getNodeType() == Node.ELEMENT_NODE) {
				if ("p".equals(tempNode.getNodeName())) {
					sb.append("<p/>").append(CRLF);
				} else if ("ul".equals(tempNode.getNodeName())) {
					sb.append("<ul>").append(CRLF);
				} else if ("li".equals(tempNode.getNodeName())) {
					sb.append("<li>").append(CRLF);
				} else if ("b".equals(tempNode.getNodeName())) {
					sb.append("<b>").append(CRLF);
				} else if ("red".equals(tempNode.getNodeName())) {
					sb.append("<font color=\"red\">").append(CRLF);
				}
				if (tempNode.hasAttributes()) {
					// get attributes names and values
					NamedNodeMap nodeMap = tempNode.getAttributes();
					for (int i = 0; i < nodeMap.getLength(); i++) {
						Node node = nodeMap.item(i);
						System.out.println(String.format("%sattr name : " + node.getNodeName(), indent));
						System.out.println(String.format("%sattr value : " + node.getNodeValue(), indent));
					}
				}
				if (tempNode.hasChildNodes()) {
					// loop again if has child nodes
					printNode(tempNode.getChildNodes(), sb, index + 1);
				}
				if ("red".equals(tempNode.getNodeName())) {
					sb.append("</font>").append(CRLF);
				} else if ("b".equals(tempNode.getNodeName())) {
					sb.append("</b>").append(CRLF);
				} else if ("li".equals(tempNode.getNodeName())) {
					sb.append("</li>").append(CRLF);
				} else if ("ul".equals(tempNode.getNodeName())) {
					sb.append("</ul>").append(CRLF);
				}
				System.out.println(String.format("%sNode Name =" + tempNode.getNodeName() + " [CLOSE]", indent));
			} else {
				System.out.println(String.format("%sUnexpected Node Type =" + tempNode.getNodeType(), indent));
			}
		}
	}

}

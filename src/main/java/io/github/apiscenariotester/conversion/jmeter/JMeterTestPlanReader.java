package io.github.apiscenariotester.conversion.jmeter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.ConvertedScenario;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.helpers.DefaultHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class JMeterTestPlanReader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversionResult read(Path input) throws IOException {
        try {
            var builder = newFactory().newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(input.toFile());
            Map<String, String> common = readCommon(document);
            List<String> warnings = readWarnings(document);
            List<ConvertedScenario> scenarios = readSamplers(document);
            return new ConversionResult(scenarios, warnings, common);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Invalid JMeter JMX: " + exception.getMessage(), exception);
        }
    }

    private static DocumentBuilderFactory newFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Map<String, String> readCommon(Document document) {
        Map<String, String> values = new LinkedHashMap<>();
        Element threadGroup = first(document, "ThreadGroup");
        values.put("sessions", prop(threadGroup, "ThreadGroup.num_threads", "1"));
        values.put("iterations", prop(threadGroup, "LoopController.loops", "1"));
        Element uniform = first(document, "UniformRandomTimer");
        Element constant = first(document, "ConstantTimer");
        if (uniform != null) {
            long minimum = parseLong(prop(uniform, "ConstantTimer.delay", "0"));
            long range = parseLong(prop(uniform, "RandomTimer.range", "0"));
            values.put("waitPattern", "RANDOM_RANGE");
            values.put("waitMinMs", Long.toString(minimum));
            values.put("waitMaxMs", Long.toString(minimum + range));
        } else if (constant != null) {
            String delay = prop(constant, "ConstantTimer.delay", "0");
            values.put("waitPattern", "FIXED");
            values.put("waitMinMs", delay);
            values.put("waitMaxMs", delay);
        }
        return values;
    }

    private List<ConvertedScenario> readSamplers(Document document) throws IOException {
        List<ConvertedScenario> scenarios = new ArrayList<>();
        NodeList samplers = document.getElementsByTagName("HTTPSamplerProxy");
        for (int index = 0; index < samplers.getLength(); index++) {
            Element sampler = (Element) samplers.item(index);
            String protocol = prop(sampler, "HTTPSampler.protocol", "http");
            String domain = prop(sampler, "HTTPSampler.domain", "");
            String port = prop(sampler, "HTTPSampler.port", "");
            String baseUrl = protocol + "://" + domain + (port.isBlank() ? "" : ":" + port);
            String body = "true".equals(prop(sampler, "HTTPSampler.postBodyRaw", "false"))
                    ? prop(sampler, "Argument.value", "")
                    : "";
            scenarios.add(new ConvertedScenario(
                    index + 1,
                    sampler.getAttribute("testname"),
                    prop(sampler, "HTTPSampler.method", "GET").toUpperCase(Locale.ROOT),
                    baseUrl,
                    prop(sampler, "HTTPSampler.path", "/"),
                    headersJson(sampler),
                    body));
        }
        return scenarios;
    }

    private String headersJson(Element sampler) throws IOException {
        ObjectNode headers = objectMapper.createObjectNode();
        Element samplerTree = nextElement(sampler);
        if (samplerTree != null && "hashTree".equals(samplerTree.getTagName())) {
            NodeList managers = samplerTree.getElementsByTagName("HeaderManager");
            for (int i = 0; i < managers.getLength(); i++) {
                Element manager = (Element) managers.item(i);
                NodeList elements = manager.getElementsByTagName("elementProp");
                for (int j = 0; j < elements.getLength(); j++) {
                    Element header = (Element) elements.item(j);
                    String name = prop(header, "Header.name", "");
                    if (!name.isBlank()) {
                        headers.put(name, prop(header, "Header.value", ""));
                    }
                }
            }
        }
        return objectMapper.writeValueAsString(headers);
    }

    private static List<String> readWarnings(Document document) {
        List<String> warnings = new ArrayList<>();
        for (String tag : List.of("JSONPostProcessor", "RegexExtractor", "ResponseAssertion")) {
            NodeList nodes = document.getElementsByTagName(tag);
            for (int index = 0; index < nodes.getLength(); index++) {
                Element element = (Element) nodes.item(index);
                warnings.add(tag + " is not converted: " + element.getAttribute("testname"));
            }
        }
        return warnings;
    }

    private static Element first(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String prop(Element root, String name, String defaultValue) {
        if (root == null) return defaultValue;
        NodeList nodes = root.getElementsByTagName("stringProp");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if (name.equals(element.getAttribute("name"))) return element.getTextContent();
        }
        NodeList booleans = root.getElementsByTagName("boolProp");
        for (int index = 0; index < booleans.getLength(); index++) {
            Element element = (Element) booleans.item(index);
            if (name.equals(element.getAttribute("name"))) return element.getTextContent();
        }
        return defaultValue;
    }

    private static Element nextElement(Node node) {
        Node next = node.getNextSibling();
        while (next != null && next.getNodeType() != Node.ELEMENT_NODE) next = next.getNextSibling();
        return (Element) next;
    }

    private static long parseLong(String value) {
        return Long.parseLong(value.trim());
    }
}

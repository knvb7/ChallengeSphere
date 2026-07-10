package com.ironside.challengesphere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class BuildSecretExclusionTest {

    private static final Set<String> APPLICATION_PATTERNS = Set.of(
            "**/application*.yml",
            "**/application*.yaml",
            "**/application*.properties");
    private static final Set<String> KEY_PATTERNS = Set.of("key.json", "**/key.json");
    private static final Set<String> UNFILTERED_EXCLUDES = Set.of(
            "**/application*.yml",
            "**/application*.yaml",
            "**/application*.properties",
            "key.json",
            "**/key.json");

    @Test
    void mainResourcesPreserveSpringBootFilteringAndExcludeServiceAccountKeys() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        Element build = onlyDirectChild(pom.getDocumentElement(), "build");
        Element resources = onlyDirectChild(build, "resources");

        List<Element> mainResources = directChildren(resources, "resource");
        assertEquals(2, mainResources.size(),
                "Expected exactly two direct <resource> entries for src/main/resources");
        assertTrue(mainResources.stream().allMatch(resource -> "src/main/resources".equals(
                        onlyDirectChild(resource, "directory").getTextContent().trim())),
                "Every direct <resource> must target src/main/resources");

        Element filtered = resourceWithFiltering(mainResources, true);
        assertEquals(APPLICATION_PATTERNS,
                childTextValues(onlyDirectChild(filtered, "includes"), "include"));
        assertEquals(KEY_PATTERNS,
                childTextValues(onlyDirectChild(filtered, "excludes"), "exclude"));

        Element unfiltered = resourceWithFiltering(mainResources, false);
        assertEquals(UNFILTERED_EXCLUDES,
                childTextValues(onlyDirectChild(unfiltered, "excludes"), "exclude"));
    }

    private static Element resourceWithFiltering(List<Element> resources, boolean filtering) {
        List<Element> matches = resources.stream()
                .filter(resource -> Boolean.toString(filtering).equals(
                        onlyDirectChild(resource, "filtering").getTextContent().trim()))
                .toList();
        assertEquals(1, matches.size(),
                () -> "Expected exactly one resource with <filtering>" + filtering + "</filtering>");
        return matches.get(0);
    }

    private static Set<String> childTextValues(Element parent, String localName) {
        return directChildren(parent, localName).stream()
                .map(child -> child.getTextContent().trim())
                .collect(Collectors.toSet());
    }

    private static Element onlyDirectChild(Element parent, String localName) {
        List<Element> matches = directChildren(parent, localName);
        assertEquals(1, matches.size(),
                () -> "Expected exactly one direct <" + localName + "> under <"
                        + parent.getLocalName() + ">");
        return matches.get(0);
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(child.getLocalName())) {
                matches.add((Element) child);
            }
        }
        return matches;
    }
}

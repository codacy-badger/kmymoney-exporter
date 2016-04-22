package fr.bellingard.accountmanager.kmymoney;

import fr.bellingard.accountmanager.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 *
 */
public class KMyMoneyReader {

    private Path kmyFile;

    private KMyMoneyReader() {
    }

    public static KMyMoneyReader on(Path kmyFile) {
        KMyMoneyReader reader = new KMyMoneyReader();
        reader.kmyFile = kmyFile;
        return reader;
    }

    public void populate(Repository repository) {
        try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(kmyFile.toFile()))) {
            DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
            domFactory.setNamespaceAware(true);
            DocumentBuilder builder = domFactory.newDocumentBuilder();
            Document xmlDoc = builder.parse(input);

            loadInstitutions(repository, xmlDoc);
            loadPayees(repository, xmlDoc);
            loadAccounts(repository, xmlDoc);
            loadTransactions(repository, xmlDoc);
        } catch (IOException e) {
            // TODO pbm with file -  do something
            e.printStackTrace();
        } catch (ParserConfigurationException | SAXException | XPathExpressionException e) {
            // TODO pbm with content of the file -  do something
            e.printStackTrace();
        }
    }

    private void loadInstitutions(Repository repository, Document xmlDoc) throws XPathExpressionException {
        NodeList nodes = getXPathResults(xmlDoc, "//INSTITUTIONS/INSTITUTION");
        Element institutionElement;
        for (int i = 0; i < nodes.getLength(); i++) {
            institutionElement = (Element) nodes.item(i);

            String id = institutionElement.getAttribute("id");
            String name = institutionElement.getAttribute("name").trim();
            repository.addInstitution(new Institution(id, name));
        }
    }

    private void loadPayees(Repository repository, Document xmlDoc) throws XPathExpressionException {
        NodeList nodes = getXPathResults(xmlDoc, "//PAYEES/PAYEE");
        Element payeeElement;
        for (int i = 0; i < nodes.getLength(); i++) {
            payeeElement = (Element) nodes.item(i);

            String id = payeeElement.getAttribute("id");
            String name = payeeElement.getAttribute("name").trim();
            repository.addPayee(new Payee(id, name));
        }
    }

    private void loadAccounts(Repository repository, Document xmlDoc) throws XPathExpressionException {
        Map<String, Account> accounts = new HashMap<>();
        NodeList nodes = getXPathResults(xmlDoc, "//ACCOUNTS/ACCOUNT");
        Element accountElement;
        // First create all accounts
        for (int i = 0; i < nodes.getLength(); i++) {
            accountElement = (Element) nodes.item(i);

            String id = accountElement.getAttribute("id");
            String name = accountElement.getAttribute("name").trim();
            Account account = new Account(id, name);
            accounts.put(id, account);

            String institutionId = accountElement.getAttribute("institution");
            Optional<Institution> institution = repository.findInstitution(institutionId);
            if (institution.isPresent()) {
                // this is a bank account
                repository.addBankAccount(account);
                account.setInstitution(institution.get());
                account.setAccountNumber(accountElement.getAttribute("number").trim());
            } else {
                // this is a category
                repository.addCategory(account);
            }
        }
        // Then create hierarchy of existing accounts
        for (int i = 0; i < nodes.getLength(); i++) {
            accountElement = (Element) nodes.item(i);
            Account account = accounts.get(accountElement.getAttribute("id"));
            Account parent = accounts.get(accountElement.getAttribute("parentaccount"));
            if (parent != null) {
                account.setParent(parent);
            }
        }
    }

    private void loadTransactions(Repository repository, Document xmlDoc) throws XPathExpressionException {
        NodeList nodes = getXPathResults(xmlDoc, "//TRANSACTIONS/TRANSACTION");
        Element transactionElement;
        // First create all accounts
        for (int i = 0; i < nodes.getLength(); i++) {
            transactionElement = (Element) nodes.item(i);

            String id = transactionElement.getAttribute("id");
            String date = transactionElement.getAttribute("postdate");
            Account fromAccount = null;
            Account toAccount = null;
            Long amount = null;
            Payee payee = null;
            String description = null;

            Element[] splits = extractSplits(transactionElement);

            Element firstElement = splits[0];
            payee = repository.findPayee(firstElement.getAttribute("payee")).orElse(null);
            fromAccount = repository.findBankAccount(firstElement.getAttribute("account")).orElse(null);
            amount = convert(firstElement.getAttribute("shares"));
            description = firstElement.getAttribute("memo").trim();

            Element secondElement = splits[1];
            toAccount = repository.findCategory(secondElement.getAttribute("account")).orElse(null);
            if (toAccount == null) {
                // this is a transfer between 2 back accounts
                toAccount = repository.findBankAccount(secondElement.getAttribute("account")).orElse(null);
            }

            Transaction transaction = new Transaction(id, fromAccount, toAccount, date, amount);
            transaction.setPayee(payee);
            transaction.setDescription(description);
        }
    }

    private Element[] extractSplits(Element transactionElement) {
        // There are only 2 splits
        Element[] splits = new Element[2];
        int index = 0;
        NodeList splitsChildren = transactionElement.getChildNodes().item(1).getChildNodes();
        for (int j = 0; j < splitsChildren.getLength(); j++) {
            Node current = splitsChildren.item(j);
            if (current.getNodeName().equals("SPLIT")) {
                splits[index++] = ((Element) current);
            }
        }
        return splits;
    }

    private Long convert(String value) {
        String[] data = value.split("/");
        long numerator = Long.parseLong(data[0]);
        long denominator = Long.parseLong(data[1]);
        return new Long(numerator * 100 / denominator);
    }

    private NodeList getXPathResults(final Node node, final String query)
            throws XPathExpressionException {
        final XPathFactory factory = XPathFactory.newInstance();
        final XPath xpath = factory.newXPath();
        final XPathExpression expr = xpath.compile(query);
        return (NodeList) expr.evaluate(node, XPathConstants.NODESET);
    }

}

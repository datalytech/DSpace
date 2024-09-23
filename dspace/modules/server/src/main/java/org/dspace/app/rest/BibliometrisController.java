package org.dspace.app.rest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Context;
import org.dspace.discovery.IndexingService;
import org.dspace.discovery.indexobject.IndexableItem;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.workflow.WorkflowItem;
import org.dspace.workflow.WorkflowService;
import org.dspace.workflow.factory.WorkflowServiceFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/" + "core/" + "bibliometris")
public class BibliometrisController {

    protected ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    protected ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    protected CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();

    protected WorkspaceItemService workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    protected InstallItemService installItemService = ContentServiceFactory.getInstance().getInstallItemService();
    protected EPersonService epersonService = EPersonServiceFactory.getInstance().getEPersonService();
    protected IndexingService indexingService = DSpaceServicesFactory.getInstance().getServiceManager()
            .getServiceByName(IndexingService.class.getName(),
                    IndexingService.class);

    @Autowired
    DiscoverableEndpointsService discoverableEndpointsService;

    private static Logger logger = org.apache.logging.log4j.LogManager
            .getLogger(BibliometrisController.class);

    public static String status = "inactive";
    public static int counterOk = 0;
    public static  int counterNotOk = 0;
    public static  int counterAll = 0;
    public static  int counter = 0;
    public static  int total = 0;
    public static  String exportApi = "";
    public static  String email = "";
    public static  String isPublish = "";
    public static  boolean importInProgress = false;
    public static  String firstWfId = "";

    public static List<String> logs = new ArrayList<>();

    @RequestMapping(method = { RequestMethod.GET, RequestMethod.HEAD })
    public ResponseEntity get(HttpServletRequest request) {
        logs = new ArrayList<>();
        status = "Connecting with bibliometris...";
        counterOk = 0;
        counterNotOk = 0;
        total = 0;
        counter = 0;

        exportApi = request.getParameter("exportApi");
        importInProgress = true;
        email = request.getParameter("email");
        if (email == null || email.isBlank()) {
            email = configurationService.getProperty("bibliometris.export.submitter");
        }

        isPublish = request.getParameter("publish");
        if (isPublish == null) isPublish = "false";
        boolean publish = false;
        if (isPublish.equals("true")) {  
            publish = true;
        }

        updateSession(request);

        String bibliometrisUrl = configurationService.getProperty("bibliometris.url");
        String loginApi = configurationService.getProperty("bibliometris.login.endpoint");
        if (exportApi == null) {
            exportApi = configurationService.getProperty("bibliometris.export.endpoint");
        }
        String user = configurationService.getProperty("bibliometris.username");
        String pass = configurationService.getProperty("bibliometris.password");

        String accessToken = "";
        try {
            accessToken = getToken(bibliometrisUrl, loginApi, user, pass);
            JSONArray exportAsJsonArray = getExportAsJsonArray(accessToken, bibliometrisUrl, exportApi);
            if (exportAsJsonArray != null) {
                total = exportAsJsonArray.size();
                status = "Successfully got data from Bibliometris. Ready to import " + total + " items";
                addLog("ok", "Successfully got data from Bibliometris. Ready to import " + total + " items");
                updateSession(request);
                status = "Starting import....";
                updateSession(request);
                try {
                    addItems(request, exportAsJsonArray, publish);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                status = "completed";
                importInProgress = false;
                updateSession(request);
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException();
        }

        if (status.equals("completed")) importInProgress = false;
        updateSession(request); 

        JSONObject jo = new JSONObject();
        jo.put("importInProgress", request.getSession().getAttribute("importInProgress"));
        jo.put("total", request.getSession().getAttribute("total"));
        jo.put("status", request.getSession().getAttribute("status"));
        return new ResponseEntity<>(jo.toString(), HttpStatus.OK);
    }

    private JSONArray getExportAsJsonArray(String token, String bibliometrisUrl, String exportApi) {

        try {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.getMessageConverters()
                    .add(0, new StringHttpMessageConverter(Charset.forName("UTF-8")));

            String endpoint = bibliometrisUrl + exportApi;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("Authorization", "Bearer " + token);

            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint, HttpMethod.GET, requestEntity, String.class);

            String result = response.getBody();

            JSONParser parser = new JSONParser();
            JSONObject jsonResponse = null;
            jsonResponse = (JSONObject) parser.parse(result);
            if (jsonResponse.containsKey("data")) {
                JSONArray data = (JSONArray) jsonResponse.get("data");
                addLog("ok", " " + ":: Successfully retrieved data from Bibliometris");

                return data;
            }
        } catch (Exception e) {
            addLog("notok", " " + ":: Failed to fetch data from bibliometris exportApi:"
                    + exportApi + ", "
                    + e.getMessage() + e.getCause());
            e.printStackTrace();
        }
        addLog("notok", " " + ":: Empty data from bibliometris exportApi:" + exportApi);

        return null;

    }

    private String getToken(String bibliometrisUrl, String loginApi, String user, String pass) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters()
                .add(0, new StringHttpMessageConverter(Charset.forName("UTF-8")));

        String endpoint = bibliometrisUrl + loginApi + "?email=" + user + "&password=" + pass;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> mrequest = new HttpEntity<String>("{\n" + //
                "    \"email\":\"" + user + "\",\n" + //
                "    \"password\":\"" + pass + "\"\n" + //
                "}", headers);

        JSONParser parser = new JSONParser();
        JSONObject jsonResponse = null;
        try {
            String mresponse = restTemplate.postForObject(
                    endpoint, mrequest, String.class);

            jsonResponse = (JSONObject) parser.parse(mresponse);
            if (jsonResponse.containsKey("access_token")) {
                String accessToken = jsonResponse.get("access_token").toString();
                return accessToken;
            }
        } catch (ParseException e) {
            // TODO Auto-generated catch block
        }
        return null;
    }

    private void addItems(HttpServletRequest request, JSONArray data, boolean publish)
            throws NumberFormatException, SQLException, AuthorizeException, IOException {
        Item item = null;
        counterAll = 0;
        counterOk = 0;
        counterNotOk = 0;
        // iterate through Items
        Iterator iterator = data.iterator();
        // int counter = 0;

        while (iterator.hasNext()) {
            updateSession(request);

            counterAll++;
            JSONObject itemBibiometris = (JSONObject) iterator.next();
            Long itemBibiometrisId = (Long) itemBibiometris.get("id");
            // log.info("item id:" + itemBibiometrisId);

            try {
                // TODO: Empty Name for Author may lead to update name at the database for
                // research with empty value
                // if (checkIfAuthorExists(itemBibiometris.get("author_id").toString()) == null)
                // {
                // // main author doesn't exist so skip item
                // addLog("notok", itemBibiometrisId + "::main-author-is-missing ("
                // + itemBibiometris.get("author_id").toString() + ")");
                // counterNotOk++;

                // continue;
                // }
                // Fetch collection to import
                String collectionToImport = configurationService.getProperty("bibliometris.collection.id");
                if (itemBibiometris.containsKey("export_type")) {
                    if (itemBibiometris.get("export_type") != null) {
                        String collectionFromJson = itemBibiometris.get("export_type").toString();
                        if (collectionFromJson != null && !collectionFromJson.equals("null")) {
                            try {
                                int collectionFromJsonAsInt = Integer.parseInt(collectionFromJson);
                                collectionToImport = collectionFromJson + "";
                            } catch (Exception e) {
                                addLog("notok", itemBibiometrisId + "::collection-not-parsable ("
                                        + itemBibiometris.get("export_type").toString() + ")");
                            }
                            collectionToImport = collectionFromJson + "";
                        }
                    }
                }
                collectionToImport = "afbb3157-2c89-4927-97fa-e04c39aeed2f";

                // Collection collection = collectionService.find(context,
                // UUID.fromString(collectionToImport));

                item = addItem(itemBibiometris, collectionToImport, false, publish);
                if (item != null) {
                    counterOk++;
                    addLog("ok", itemBibiometrisId + ":: Successfully added item");

                }

            } catch (Exception e) {
                addLog("notok", itemBibiometrisId + "::" + e.getMessage() + "-" +
                        e.getCause() + "___");
                System.out.println(e.getMessage() + e.getCause());
                counterNotOk++;
            }
            status = "Importing item " + counterAll + " / " + total;

        }
        status = "completed";
        addLog("ok", " " + ":: Successful Import - Completed");

    }

    private void sleep(int mtime) {
        try {
            Thread.sleep(mtime);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
        }
    }

    private Item addItem(JSONObject itemData, String mycollection, boolean template, boolean publish)
            throws Exception {

        Context context = null;
        context = new Context();
        Item myitem = null;

        try {
            EPerson myEPerson = null;
            myEPerson = epersonService.findByEmail(context, email);
            context.setCurrentUser(myEPerson);

            context.turnOffAuthorisationSystem();

            // only for workflow items, reindex the first
            if (!firstWfId.equals("")) {
                Item oldItem = itemService.find(context, UUID.fromString(firstWfId));
                IndexableItem indexableItem = new IndexableItem(oldItem);
                indexingService.indexContent(context, indexableItem, true);
                context.commit();
            }

            JSONObject publication = (JSONObject) itemData.get("publication");
            // TODO: check if identifier structure is correct
            String identifier = itemData.get("source") + "-" + publication.get("id").toString();
            // Check if item already exists
            Iterator<Item> items = itemService.findUnfilteredByMetadataField(context, "dc", "identifier", null,
                    identifier);
            if (items.hasNext()) {
                // addLog("notok", identifier + "::item-already-exists [dc.identifier.null]" +
                //         "___");
                // counterNotOk++;
                // return null;
            }

            // Find the EPerson, assign to context
            WorkspaceItem wi = null;
            Collection collection = collectionService.find(context, UUID.fromString(mycollection));

            wi = workspaceItemService.create(context, collection, template);

            WorkflowItem wfItem = null;
            myitem = wi.getItem();

            // Iterate through publication
            Iterator<String> keys = publication.keySet().iterator();
            while (keys.hasNext()) {
                String publicationElement = keys.next();
                // Check for empty value
                if (publication.get(publicationElement) == null) {
                    continue;
                }
                // Check if key exists in mapping configuration or is empty
                String mapProperty = configurationService
                        .getProperty(itemData.get("source") + "." + publicationElement);
                if (mapProperty == null || mapProperty.trim().equals("") || mapProperty.startsWith("scopus")
                        || mapProperty.startsWith("google_scholar")
                        || mapProperty.startsWith("wos") || mapProperty.startsWith("pubmed")
                        || mapProperty.startsWith("orcid") || mapProperty.startsWith("http")) {
                    // do nothing
                    continue;
                }
                String[] mapPropertySplit = mapProperty.split("\\.");
                String schema = mapPropertySplit[0];
                String element = mapPropertySplit[1].trim();
                String qualifier = null;
                if (mapPropertySplit.length > 2) {
                    qualifier = mapPropertySplit[2].trim();
                }

                String valueTobeStored = publication.get(publicationElement).toString();
                if (element.equals("subject") && qualifier == null) { // keywords
                    String[] kpartsPipes = valueTobeStored.split("\\|");
                    for (String kpartPipe : kpartsPipes) {
                        String[] kpartsComas = kpartPipe.split(",");
                        for (String kpartsComa : kpartsComas) {
                            String[] kpartDots = kpartsComa.split(":");
                            for (String kpartDot : kpartDots) {
                                itemService.addMetadata(context, myitem, schema, element, qualifier, null,
                                        kpartDot.trim());
                            }
                        }

                    }
                } else {
                    String valueToStore = publication.get(publicationElement).toString();
                    if ((schema.equals("dc") && element.equals("link")) ||
                            (schema.equals("dc") && element.equals("identifier") && qualifier != null
                                    && (qualifier.equals("doi") || qualifier.equals("scopus")
                                            || qualifier.equals("url")))) {

                        boolean itemExists = isItemAlreadyPublishedInRepo(context, schema, element, qualifier,
                                valueToStore);
                        if (!itemExists) {
                            itemService.addMetadata(context, myitem, schema, element, qualifier, null, valueToStore);

                        } else {
                            addLog("notok", identifier + "::item-already-exists [" + schema + "." +
                                    element + "." + qualifier + "]" + "___");
                            counterNotOk++;

                            return null;
                        }
                    } else {
                        itemService.addMetadata(context, myitem, schema, element, qualifier, null, valueToStore);
                    }
                }

            }

            itemService.addMetadata(context, myitem, "dc", "identifier", null, null, identifier);

            List<String> resultNames = new ArrayList();

            itemService.clearMetadata(context, myitem, "dc", "contributor", "author", null);

            itemData.get("author_id"); // rp code
            JSONObject author = new JSONObject();
            author.put(itemData.get("author_id"), "");
            // Add main Author
            List<String> mainAuthors = addAuthorsToItem(context, author, myitem, resultNames);
            for (String name : mainAuthors) {
                resultNames.add(name.trim());
            }

            JSONObject coAuthors = (JSONObject) itemData.get("coauthors");
            if (coAuthors != null) {
                List<String> mcoAuthors = addAuthorsToItem(context, coAuthors, myitem, resultNames);
                for (String name : mcoAuthors) {
                    resultNames.add(name.trim());
                }
            }

            // Add everybody else from publication.authors value
            if (publication != null && publication.containsKey("authors")) {
                String allauthors = publication.get("authors").toString();
                String[] authorParts = allauthors.split(",");
                for (String cauthor : authorParts) {
                    String fcauthor = cauthor.trim().replace(" ", ", ");
                    if (!resultNames.contains(fcauthor) && !fcauthor.equals("\\u2026")) {
                        JSONObject otherauthor = new JSONObject();
                        otherauthor.put("notexists", fcauthor);
                        List<String> otherAuthor = addAuthorsToItem(context, otherauthor, myitem, resultNames);
                        for (String name : otherAuthor) {
                            resultNames.add(name.trim());
                        }
                    }
                }
            }

            // static fields
            for (int i = 1; configurationService.getProperty("static.field." + i) != null; i++) {
                String staticField = configurationService.getProperty("static.field." + i);
                if (!staticField.startsWith("static.field")) {
                    String[] staticFieldParts = staticField.split("::");
                    if (staticFieldParts.length == 3) {
                        String element = staticFieldParts[0];
                        String val = staticFieldParts[1];
                        String collectionsFromConfig = staticFieldParts[2];
                        List<String> collectionsFromConfigList = new ArrayList<>();
                        for (String currentColl : collectionsFromConfig.split(",")) {
                            collectionsFromConfigList.add(currentColl.trim());
                        }
                        if (collectionsFromConfig.equals("all")
                                || collectionsFromConfigList.contains(collection.getID().toString())) {
                            String[] elementParts = element.split("\\.");
                            if (elementParts.length == 3) {
                                String staticQual = elementParts[2];
                                if (staticQual.equals("null"))
                                    staticQual = null;
                                itemService.addMetadata(context, myitem, elementParts[0], elementParts[1], staticQual,
                                        null,
                                        val);
                            }
                        }

                    }
                } else {
                    break;
                }

            }
            if (!publish) {
                myitem.setArchived(false);
                myitem.setDiscoverable(true);
                itemService.update(context, myitem);
            } else {
                WorkflowService workflowService = WorkflowServiceFactory.getInstance().getWorkflowService();
                wfItem = workflowService.startWithoutNotify(context, wi);
                myitem.setOwningCollection(collection);
                myitem.setSubmitter(myEPerson);
                installItemService.installItem(context, wi);
            }

            context.commit();
            context.restoreAuthSystemState();
            context.complete();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException();
        } finally {
            if (context != null && context.isValid()) {
                context.abort();
            }
        }

        if (firstWfId.equals(""))
            firstWfId = myitem.getID().toString();
        return myitem;
    }

    // private ResearcherPage checkIfAuthorExists(String authorRP) {
    // DSpace dspace = new DSpace();
    // // Check if author exists in researchers page table
    // ApplicationService applicationService = dspace.getServiceManager()
    // .getServiceByName("applicationService",
    // ApplicationService.class);
    // ResearcherPage rp = applicationService.getEntityByCrisId(authorRP);
    // if (rp == null || rp.getAllNames() == null || rp.getAllNames().get(0) ==
    // null) {
    // return null;
    // }
    // return rp;
    // }

    private void addLog(String lstatus, String log) {
        counter++;
        logs.add(counter + "::" + lstatus + "::" + log);
    }

    private boolean isItemAlreadyPublishedInRepo(Context c, String schema, String element, String qualifier,
            String valueToStore) {
        try {
            Iterator<Item> items = itemService.findUnfilteredByMetadataField(c, "dc", element, qualifier,
                    valueToStore);
            if (items.hasNext()) {
                return true;
            }
        } catch (Exception e) {
            // log.error("Error while checking existance for dublicates in :" + schema + "."
            // + element + "." + qualifier);
            e.printStackTrace();
        }
        return false;
    }

    private List<String> addAuthorsToItem(Context c, JSONObject coAuthors, Item myItem, List<String> resultNames) {
        // Iterate through coAuthors
        List<String> currentnames = new ArrayList();
        Iterator<String> keys = coAuthors.keySet().iterator();
        while (keys.hasNext()) {
            String coAuthorRp = keys.next();
            String coAuthor = coAuthors.get(coAuthorRp).toString();
            // ResearcherPage rp = checkIfAuthorExists(coAuthorRp);
            // Add (co)Author only if exists to RP table
            // if (rp != null && rp.getAllNames() != null && rp.getAllNames().get(0) !=
            // null) {
            // coAuthor = rp.getAllNames().get(0);
            // if (coAuthor != null && !resultNames.contains(coAuthor.trim())) {
            // itemService.addMetadata(c, myItem ,"dc", "contributor", "author", null,
            // coAuthor,
            // coAuthorRp, 600);
            // currentnames.add(coAuthor);
            // }
            // } else {
            // if (!resultNames.contains(coAuthor.trim())) {
            // itemService.addMetadata(c, myItem ,"dc", "contributor", "author", null,
            // coAuthor);
            // currentnames.add(coAuthor);
            // }
            // }
            try {
                if (coAuthor != null && !coAuthor.trim().equals(""))
                    itemService.addMetadata(c, myItem, "dc", "contributor", "author", null, coAuthor);
            } catch (Exception e) {
                e.printStackTrace();
            }
            currentnames.add(coAuthor);
        }
        return currentnames;
    }

    private void updateSession(HttpServletRequest request) {
        request.getSession().setAttribute("importInProgress", importInProgress);
        request.getSession().setAttribute("total", total);
        request.getSession().setAttribute("counterOk", counterOk);
        request.getSession().setAttribute("counterNotOk", counterNotOk);
        request.getSession().setAttribute("status", status);
        request.getSession().setAttribute("exportApi", exportApi);
        request.getSession().setAttribute("email", email);
        request.getSession().setAttribute("publish", isPublish);
        request.getSession().setAttribute("logs", logs);
        sleep(3000);
    }

}

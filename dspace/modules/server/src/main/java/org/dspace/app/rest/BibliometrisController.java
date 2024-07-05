package org.dspace.app.rest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections.map.MultiValueMap;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;


@RestController
@RequestMapping("/api/" + "core/" + "bibliometris")
public class BibliometrisController {

    protected ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

    @Autowired
    DiscoverableEndpointsService discoverableEndpointsService;

    private static Logger logger = org.apache.logging.log4j.LogManager
            .getLogger(BibliometrisController.class);

    private static String status = "inactive";
    private static int counterOk = 0;
    private static int counterNotOk = 0;
    private static int counterAll = 0;
    private static int counter = 0;
    private static int total = 0;
    private static int progressBar = 1;

    private static List<String> logs = new ArrayList<>();

    @RequestMapping(method = { RequestMethod.GET, RequestMethod.HEAD })
    public ResponseEntity<String> retrieve(HttpServletResponse response,
        HttpServletRequest request) throws IOException, SQLException, AuthorizeException {
        String bibliometrisUrl = configurationService.getProperty("bibliometris.url");
        String loginApi = configurationService.getProperty("bibliometris.login.endpoint");
        String exportApi = configurationService.getProperty("bibliometris.export.endpoint");
        String user = configurationService.getProperty("bibliometris.username");
        String pass = configurationService.getProperty("bibliometris.password");

        String accessToken = "";
        try {
            accessToken = getToken(bibliometrisUrl, loginApi, user, pass);
        } catch (Exception e) {
            logger.error("Cannot get token from Bibliometris:" + e.getMessage());
        }

        JSONObject jo = new JSONObject();
        jo.put("method", "GET");
        jo.put("accessToken", accessToken);
        jo.put("status", HttpStatus.OK);
        return new ResponseEntity<>(jo.toString(), HttpStatus.OK);

    }

    @RequestMapping(method = { RequestMethod.POST, RequestMethod.HEAD })
    public ResponseEntity post(HttpServletRequest request, @RequestParam(name = "var", required = false) String var) {
        String bibliometrisUrl = configurationService.getProperty("bibliometris.url");
        String loginApi = configurationService.getProperty("bibliometris.login.endpoint");
        String exportApi = configurationService.getProperty("bibliometris.export.endpoint");
        String user = configurationService.getProperty("bibliometris.username");
        String pass = configurationService.getProperty("bibliometris.password");

        String accessToken = "";
        try {
            // accessToken = getToken(bibliometrisUrl, loginApi, user, pass);
        } catch (Exception e) {
            logger.error("Cannot get token from Bibliometris:" + e.getMessage());
        }

        JSONObject jo = new JSONObject();
        jo.put("method", "POST");
        jo.put("accessToken", accessToken);
        jo.put("status", HttpStatus.OK);
        return new ResponseEntity<>(jo.toString(), HttpStatus.OK);
    }

    private String getToken(String bibliometrisUrl, String loginApi, String user, String pass) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters()
                .add(0, new StringHttpMessageConverter(Charset.forName("UTF-8")));

        String endpoint = bibliometrisUrl + loginApi + "?email=" + user + "&password=" + pass;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> mrequest = new HttpEntity<String>("{\n" + //
                        "    \"email\":\""+user+"\",\n" + //
                        "    \"password\":\""+pass+"\"\n" + // 
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

}

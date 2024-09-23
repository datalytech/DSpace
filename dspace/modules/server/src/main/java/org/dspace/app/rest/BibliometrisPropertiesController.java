package org.dspace.app.rest;

import javax.servlet.http.HttpServletRequest;

import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/" + "core/" + "bibliometris-properties")
public class BibliometrisPropertiesController {

    @Autowired
    DiscoverableEndpointsService discoverableEndpointsService;

    @RequestMapping(method = { RequestMethod.GET, RequestMethod.HEAD })
    public ResponseEntity get(HttpServletRequest request) {
        JSONObject jo = new JSONObject();
        jo.put("importInProgress", BibliometrisController.importInProgress);
        jo.put("total", BibliometrisController.total); 
        jo.put("counterOk", BibliometrisController.counterOk);
        jo.put("counterNotOk", BibliometrisController.counterNotOk);
        jo.put("logs", BibliometrisController.logs);
        jo.put("status", BibliometrisController.status);
        jo.put("exportApi", BibliometrisController.exportApi);
        jo.put("email", BibliometrisController.email);
        jo.put("publish", BibliometrisController.isPublish);
        return new ResponseEntity<>(jo.toString(), HttpStatus.OK);

        
    }

}

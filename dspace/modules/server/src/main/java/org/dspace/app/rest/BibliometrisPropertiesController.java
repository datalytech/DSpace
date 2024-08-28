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
        jo.put("importInProgress", request.getSession().getAttribute("importInProgress"));
        jo.put("total", request.getSession().getAttribute("total")); 
        jo.put("counterOk", request.getSession().getAttribute("counterOk"));
        jo.put("counterNotOk", request.getSession().getAttribute("counterNotOk"));
        jo.put("logs", request.getSession().getAttribute("logs"));
        jo.put("status", request.getSession().getAttribute("status"));
        jo.put("exportApi", request.getSession().getAttribute("exportApi"));
        jo.put("email", request.getSession().getAttribute("email"));
        jo.put("publish", request.getSession().getAttribute("publish"));
        return new ResponseEntity<>(jo.toString(), HttpStatus.OK);

        
    }

}

package org.dspace.app.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/" + "core/" + "usage-statistics")
public class UsageStatisticsController {

    @Autowired
    DiscoverableEndpointsService discoverableEndpointsService;

    protected ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    protected ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    @RequestMapping(method = { RequestMethod.GET, RequestMethod.HEAD })
    public ResponseEntity<byte[]> downloadExcel(HttpServletRequest request) throws IOException {

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Actions");

        String reportItemUuid = configurationService.getProperty("dspace.action.report.item.uuid");
        Context context = new Context();
        Item reportItem = null;
        int counter = 1;
        try {
            reportItem = itemService.findByIdOrLegacyId(context, reportItemUuid);
            List<MetadataValue> records = itemService.getMetadata(reportItem, "dc", "subject", null, Item.ANY);
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Date");
            headerRow.createCell(1).setCellValue("User");
            headerRow.createCell(2).setCellValue("Action");
            headerRow.createCell(3).setCellValue("Title");
            headerRow.createCell(4).setCellValue("Item");


            for (MetadataValue record : records) {
                String text = record.getValue();
                String[] parts = text.split("::");
                Row row1 = sheet.createRow(counter);
                row1.createCell(0).setCellValue(parts[0]);
                row1.createCell(1).setCellValue(parts[1]);
                row1.createCell(2).setCellValue(parts[2]);
                row1.createCell(3).setCellValue(parts[4]);
                row1.createCell(4).setCellValue(configurationService.getProperty("dspace.ui.url") + "/" + parts[3]);

                counter++;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        // Write the Excel file to a byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        byte[] excelBytes = outputStream.toByteArray();

        // Close the workbook and output stream
        workbook.close();
        outputStream.close();

        // Set response headers
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=usagereport.xlsx");
        headers.add(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        // Return the Excel file as a byte array in the response
        return new ResponseEntity<>(excelBytes, headers, HttpStatus.OK);

    }

}

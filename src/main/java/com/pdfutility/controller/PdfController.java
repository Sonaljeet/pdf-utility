package com.pdfutility.controller;

import com.pdfutility.service.PdfService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class PdfController {

    @Autowired
    private PdfService pdfService;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/process")
    public String processPdfs(@RequestParam("file1") MultipartFile file1,
                              @RequestParam(value = "file2", required = false) MultipartFile file2,
                              @RequestParam(value = "verifyText", required = false) String verifyText,
                              Model model) throws Exception {
        String text1 = pdfService.extractText(file1);
        String imageText1 = pdfService.extractImageText(file1);

        model.addAttribute("text1", text1);
        model.addAttribute("imageText1", imageText1);

        if (verifyText != null && !verifyText.isEmpty()) {
            String verificationResult = pdfService.verifyMultipleTextPresence(text1, imageText1, verifyText);
            model.addAttribute("verificationResult", verificationResult);
        }

        if (file2 != null && !file2.isEmpty()) {
            String text2 = pdfService.extractText(file2);
            String diff = pdfService.compareText(text1, text2);
            model.addAttribute("text2", text2);
            model.addAttribute("diff", diff);
        }

        return "index";
    }
}
package com.classroom.modules.assessment.service;

import com.classroom.common.*;
import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.repository.AssessmentStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.classroom.modules.assessment.doc.LearnerProjection.object;

@Service
public class AssessmentCodeService {
    private final AssessmentAttemptService attempts;
    private final String endpoint,token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public AssessmentCodeService(AssessmentAttemptService attempts,
        @Value("${ASSESSMENT_SANDBOX_URL:}") String endpoint,@Value("${ASSESSMENT_SANDBOX_TOKEN:}") String token) {
        this.attempts=attempts;this.endpoint=endpoint;this.token=token;
    }
    public Map<String,Object> run(String attemptId,String itemId,String user,JsonNode body) {
        String code=body.path("code").asText();checkCode(code);
        ItemNode item=attempts.prepareCodeRun(attemptId,itemId,user);
        JsonNode tests=item.interaction().path("examples");
        if(!tests.isArray() || tests.isEmpty()) throw new AppException(ErrorCode.BAD_REQUEST,"Giáo viên chưa thêm test ví dụ");
        JsonNode results=execute(item,code,tests);int passed=0;StringBuilder output=new StringBuilder();
        for(JsonNode result:results){if(result.path("passed").asBoolean())passed++;if(output.length()<20000)output.append(result.path("output").asText()).append('\n');}
        return object("output",output.substring(0,Math.min(20000,output.length())),"passed",passed,"total",results.size());
    }
    public JsonNode execute(ItemNode item,String code,JsonNode tests) {
        checkCode(code);
        if(endpoint.isBlank() || token.length()<32) throw new AppException(ErrorCode.CONFLICT,"Môi trường chạy code chưa sẵn sàng");
        if(!tests.isArray() || tests.isEmpty() || tests.size()>20)throw new AppException(ErrorCode.BAD_REQUEST,"Test case không hợp lệ");
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint+"/execute")).timeout(Duration.ofSeconds(100))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(DocumentCodec.writeJson(object("language",item.interaction().path("language").asText(),"code",code,"tests",tests)))).build();
            HttpResponse<java.io.InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200)throw new AppException(ErrorCode.CONFLICT,"Môi trường chạy code đang bận, hãy thử lại");
                byte[] bytes=stream.readNBytes(1500000);
                if(bytes.length>=1500000)throw new AppException(ErrorCode.CONFLICT,"Kết quả chạy quá lớn");
                JsonNode results=DocumentCodec.readTree(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).path("results");
                if(!results.isArray() || results.size()!=tests.size())throw new AppException(ErrorCode.CONFLICT,"Kết quả chạy không hợp lệ");
                return results;
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new AppException(ErrorCode.CONFLICT,"Đã ngắt phiên chạy code");}
        catch(java.io.IOException e){throw new AppException(ErrorCode.CONFLICT,"Môi trường chạy code chưa kết nối được");}
    }
    private static void checkCode(String code){if(code==null || code.isBlank() || code.length()>20000)throw new AppException(ErrorCode.BAD_REQUEST,"Code cần từ 1 đến 20.000 ký tự");}
}

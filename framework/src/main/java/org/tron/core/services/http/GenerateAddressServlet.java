package org.tron.core.services.http;

import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.Wallet;
import org.tron.protos.Protocol;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

@Component
@Slf4j(topic = "API")
public class GenerateAddressServlet extends RateLimiterServlet {

    final Random random = new Random();

    @Autowired
    private Wallet wallet;

    @SneakyThrows
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileWriter fileWriter = new FileWriter("randomAddress.txt");
//        String round = request.getParameter("round");
        long roundNum = 10;
//        if (!StringUtil.isBlank(round)) {
//            try {
//                roundNum = Long.parseLong(round);
//            }
//            catch (NumberFormatException e) {
//
//            }
//        }

        int i = 0;
        while (i < roundNum) {
            byte[] address = generateAddress();
//            logger.info("address is {}, i = {}", Hex.toHexString(address), i);
            Protocol.Account.Builder build = Protocol.Account.newBuilder();
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("address", Hex.toHexString(address));
            JsonFormat.merge(jsonObject.toJSONString(), build, false);
            Protocol.Account account = wallet.getAccount(build.build());
            if (account != null) {
                continue;
            }
            i ++;
            fileWriter.write(Hex.toHexString(address) + '\n');
        }
        fileWriter.close();
    }

    protected byte[] generateAddress() {
        byte[] result = new byte[21];
        random.nextBytes(result);
        result[0] = 0x41;
        return result;
    }
}

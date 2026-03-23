import com.alibaba.cloud.ai.dataagent.DataAgentApplication;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultRequest;
import com.alibaba.cloud.ai.dataagent.service.sql.SqlResultLiteQueryService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class SemanticLiteParamRunner {

	private SemanticLiteParamRunner() {
	}

	public static void main(String[] args) {
		System.setProperty("spring.ai.alibaba.data-agent.code-executor.code-pool-executor", "AI_SIMULATION");
		System.setProperty("spring.ai.alibaba.data-agent.code-executor.codePoolExecutor", "AI_SIMULATION");
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DataAgentApplication.class)
			.web(WebApplicationType.NONE)
			.properties("spring.main.banner-mode=off")
			.properties("spring.ai.alibaba.data-agent.code-executor.code-pool-executor=AI_SIMULATION")
			.run(args)) {
			SqlResultLiteQueryService service = context.getBean(SqlResultLiteQueryService.class);
			SqlResultRequest request = new SqlResultRequest();
			request.setAgentId("6");
			request.setThreadId("semantic-lite-probe-20260323");
			request.setQuery("查询一个供水管网中管径>50的管线信息");
			System.out.println(service.query(request));
		}
	}
}

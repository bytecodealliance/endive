package run.endive.fuzz;

public class TestResult {

    private final String oracleResult;
    private final String engineResult;

    public TestResult(String oracleResult, String engineResult) {
        this.oracleResult = oracleResult;
        this.engineResult = engineResult;
    }

    public String getOracleResult() {
        return oracleResult;
    }

    public String getEngineResult() {
        return engineResult;
    }
}

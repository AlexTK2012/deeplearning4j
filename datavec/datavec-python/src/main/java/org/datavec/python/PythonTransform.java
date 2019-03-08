package org.datavec.python;

import org.datavec.api.transform.ColumnType;
import org.datavec.api.transform.Transform;
import org.datavec.api.transform.schema.Schema;
import org.datavec.api.writable.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Row-wise Transform that applies arbitrary python code on each row
 */
public class PythonTransform implements Transform{
    private String setupCode;
    private String execCode;
    private String code;
    private PythonVariables pyInputs;
    private PythonVariables pyOutputs;
    private String name;
    private Schema inputSchema;
    private Schema outputSchema;


    public PythonTransform(String code, PythonVariables pyInputs, PythonVariables pyOutputs) throws Exception{
        this.code = code;
        this.pyInputs = pyInputs;
        this.pyOutputs = pyOutputs;
        parseSetupAndExecCode();
        this.name = UUID.randomUUID().toString();
    }

    @Override
    public void setInputSchema(Schema inputSchema){
        this.inputSchema = inputSchema;
        try{
            pyInputs = schemaToPythonVariables(inputSchema);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
        if (outputSchema == null){
            outputSchema = inputSchema;
        }

    }

    @Override
    public Schema getInputSchema(){
        return inputSchema;
    }

    @Override
    public List<List<Writable>> mapSequence(List<List<Writable>> sequence) {
        List<List<Writable>> out = new ArrayList<>();
        for (List<Writable> l : sequence) {
            out.add(map(l));
        }
        return out;
    }

    @Override
    public Object map(Object input) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Object mapSequence(Object sequence) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public List<Writable> map(List<Writable> writables){
        PythonVariables pyInputs = getPyInputsFromWritables(writables);
        try{
            PythonVariables pyOutputs = PythonExecutioner.getInstance().exec(this, pyInputs);
            return getWritablesFromPyOutputs(pyOutputs);
        }
        catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public String[] outputColumnNames(){
        return pyOutputs.getVariables();
    }

    @Override
    public String outputColumnName(){
        return outputColumnNames()[0];
    }
    @Override
    public String[] columnNames(){
        return pyOutputs.getVariables();
    }

    @Override
    public String columnName(){
        return columnNames()[0];
    }

    public Schema transform(Schema inputSchema){
        return outputSchema;
    }


    private PythonVariables getPyInputsFromWritables(List<Writable> writables){

        PythonVariables ret = new PythonVariables();

        for (String name: pyInputs.getVariables()){
            int colIdx = inputSchema.getIndexOfColumn(name);
            Writable w = writables.get(colIdx);
            PythonVariables.Type pyType = pyInputs.getType(name);
            switch (pyType){
                case INT:
                    if (w instanceof LongWritable){
                        ret.addInt(name, ((LongWritable)w).get());
                    }
                    else{
                        ret.addInt(name, ((IntWritable)w).get());
                    }

                    break;
                case FLOAT:
                    if (w instanceof DoubleWritable){
                        ret.addFloat(name, ((DoubleWritable)w).get());
                    }
                    else{
                        ret.addFloat(name, ((FloatWritable)w).get());
                    }
                    break;
                case STR:
                    ret.addStr(name, ((Text)w).toString());
                    break;
                case NDARRAY:
                    ret.addNDArray(name,((NDArrayWritable)w).get());
                    break;
            }

        }
        return ret;
    }

    private List<Writable> getWritablesFromPyOutputs(PythonVariables pyOuts){
        List<Writable> out = new ArrayList<>();
        for (int i=0; i<outputSchema.numColumns(); i++){
            String name = outputSchema.getName(i);
            PythonVariables.Type pyType = pyOutputs.getType(name);
            switch (pyType){
                case INT:
                    out.add((Writable) new LongWritable(pyOuts.getIntValue(name)));
                    break;
                case FLOAT:
                    out.add((Writable) new DoubleWritable(pyOuts.getFloatValue(name)));
                    break;
                case STR:
                    out.add((Writable) new Text(pyOuts.getStrValue(name)));
                    break;
                case NDARRAY:
                    out.add((Writable) new NDArrayWritable(pyOuts.getNDArrayValue(name).getND4JArray()));
                    break;
            }
        }
        return out;
    }

    /**
     * Code between `#<SETUP>` and `#</SETUP>` tags will be
     * executed only once, while the rest of the code will be executed
     * per transaction.
     */
    private void parseSetupAndExecCode() throws Exception{
        String startTag = "#<SETUP>";
        String endTag = "#</SETUP>";
        if (code.contains(startTag) && code.contains(endTag)){
            String[] sp1 = code.split(startTag);
            if (sp1.length > 2){
                throw new Exception("Only 1 <SETUP> tag allowed.");
            }
            String sp2[] = sp1[1].split(endTag);
            if (sp2.length > 2){
                throw new Exception("Only 1 </SETUP> tag allowed.");
            }
            setupCode = sp2[0];
            execCode = sp2[1];
        }
        else{
            execCode = code;
            setupCode = null;
        }
    }
    public PythonTransform(String code) throws Exception{
        this.code = code;
        parseSetupAndExecCode();
        this.name = UUID.randomUUID().toString();
    }
    private PythonVariables schemaToPythonVariables(Schema schema) throws Exception{
        PythonVariables pyVars = new PythonVariables();
        int numCols = schema.numColumns();
        for (int i=0; i<numCols; i++){
            String colName = schema.getName(i);
            ColumnType colType = schema.getType(i);
            switch (colType){
                case Long:
                case Integer:
                    pyVars.addInt(colName);
                    break;
                case Double:
                case Float:
                    pyVars.addFloat(colName);
                    break;
                case String:
                    pyVars.addStr(colName);
                    break;
                case NDArray:
                    pyVars.addNDArray(colName);
                    break;
                default:
                    throw new Exception("Unsupported python input type: " + colType.toString());
            }
        }
        return pyVars;
    }

    public PythonTransform(String code, Schema outputSchema) throws Exception{
        this.code = code;
        parseSetupAndExecCode();
        this.name = UUID.randomUUID().toString();
        this.outputSchema = outputSchema;
        this.pyOutputs = schemaToPythonVariables(outputSchema);


    }
    public String getName() {
        return name;
    }

    public String getExecCode(){
        return execCode;
    }

    public String getSetupCode(){
        return setupCode;
    }
    public String getCode(){
        return code;
    }

    public PythonVariables getInputs() {
        return pyInputs;
    }

    public PythonVariables getOutputs() {
        return pyOutputs;
    }


}

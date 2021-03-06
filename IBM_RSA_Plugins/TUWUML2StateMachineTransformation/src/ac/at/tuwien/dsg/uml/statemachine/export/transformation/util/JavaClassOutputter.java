package ac.at.tuwien.dsg.uml.statemachine.export.transformation.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

import com.ibm.xtools.transform.core.ITransformContext;


/**
 * Helper class used to create directories and files, used in generating abstract tests and test configuration files
 * 
 * __author__ = "TU Wien, Distributed System's Group", http://www.infosys.tuwien.ac.at/
 * __copyright__ = "Copyright 2016, TU Wien, Distributed Systems Group"
 * __license__ = "Apache LICENSE V2.0"
 * __maintainer__ = "Daniel Moldovan"
 * __email__ = "d.moldovan@dsg.tuwien.ac.at"
 */

public class JavaClassOutputter {

	private static final List<String> defaultImports;
	
	static{
		defaultImports = new ArrayList<String>();
		defaultImports.add("org.eclipse.uml2.uml.*");
		defaultImports.add("org.eclipse.uml2.uml.Package"); // added because some profiles might use it and it might be confused with java.lang.Package
		defaultImports.add("org.eclipse.uml2.uml.Class"); // added because some profiles might use it and it might be confused with java Class type
	}
	
	public static void outputRawFile(ITransformContext context, Document doc, String filename){
		//generate CLass output file
		IResource res = (IResource) context.getTargetContainer(); 
		IPath targetPath = res.getLocation();
		File myFile = new File(targetPath + File.separator + filename);
		PrintWriter fw;
		try {
			fw = new PrintWriter(myFile);
			fw.write(doc.get());
			fw.flush();
			fw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


	}
	
	public static void outputFile(ITransformContext context, Document doc, String filename, String generationStrategyName, String stateMachineName){
		//generate CLass output file
		IResource res = (IResource) context.getTargetContainer(); 
		IPath targetPath = res.getLocation();
		
		CodeFormatter codeFormatter = ToolFactory.createCodeFormatter(null);
		
		String code = doc.get();
		
		TextEdit textEdit = codeFormatter.format(CodeFormatter.K_UNKNOWN, code, 0,code.length(),0,null);
		try {
			//unsure why but sometimes formatted is null
			if (textEdit != null){
				textEdit.apply(doc);
			}else{
				//usually errors appear due to spaces or illegal characters in property names
				IOException exception = new IOException("Generated document has formatting errors: \n" + code);
				throw new UncheckedIOException("Generated document has formatting errors: \n" + code, exception);
			}
			
			File myFile = new File(filename);
			PrintWriter fw;
			try {
				fw = new PrintWriter(myFile);
				
				fw.write("/* Generated by " +  generationStrategyName + " from state diagram  " + stateMachineName + " */ \n\n");
				
				for(String importString: defaultImports){
					fw.write("import " + importString +";");
					fw.write("\n");
				}
				fw.write("\n");
				fw.write(doc.get());
				fw.flush();
				fw.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		} catch (MalformedTreeException e) {
			e.printStackTrace();
		} catch (BadLocationException e) {
			e.printStackTrace();
		}   
		
	}
	
	public static void outputFiles(ITransformContext context, List<Document> documents, String className,  String generationStrategyName ){
		//generate CLass output file
		IResource res = (IResource) context.getTargetContainer(); 
		IPath targetPath = res.getLocation();

		String directoryName = String.format("%s%s%s", targetPath, File.separator, className + "Tests");
		File directory = new File(directoryName);
		directory.mkdir();

		//create individual test config files
		for (Document document : documents){
			String content = document.get();
			String name = "Test_" + UUID.randomUUID();
			
			//A little hack. Search in document to get test name, and use it as config file
			
			Pattern regex = Pattern.compile("name: \"\\w*\"");
			Matcher m = regex.matcher(content);
			if (m.find()) {
				regex = Pattern.compile("\"\\w*\"");
				content = m.group();
				m = regex.matcher(content);
				if (m.find()) {
					content = m.group();
					name = "Test_" + content.trim().substring(1,content.length()-1);
				}
			}

			File myFile = new File(directory + File.separator + name);
			PrintWriter fw;
			try {
				fw = new PrintWriter(myFile);
				fw.write(String.format("# Generated by %s for class %s \n\n", generationStrategyName, className));
				fw.write(document.get());
				fw.flush();
				fw.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

		}   

	}
	 
	
}

package ac.at.tuwien.dsg.uml.statemachine.export.transformation.engines;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AssertStatement;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.swt.widgets.Display;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.UndoEdit;
import org.eclipse.uml2.uml.CallEvent;
import org.eclipse.uml2.uml.ChangeEvent;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Constraint;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.Event;
import org.eclipse.uml2.uml.FinalState;
import org.eclipse.uml2.uml.LiteralString;
import org.eclipse.uml2.uml.OpaqueExpression;
import org.eclipse.uml2.uml.Operation;
import org.eclipse.uml2.uml.TimeEvent;
import org.eclipse.uml2.uml.Trigger;

import ac.at.tuwien.dsg.uml.statemachine.export.transformation.internal.StateMachineState;
import ac.at.tuwien.dsg.uml.statemachine.export.transformation.internal.StateMachineStateGraph;
import ac.at.tuwien.dsg.uml.statemachine.export.transformation.internal.StateMachineStateTransition;
import ac.at.tuwien.dsg.uml.statemachine.export.transformation.internal.exceptions.NoSuchStateException;
import ac.at.tuwien.dsg.uml.statemachine.export.transformation.util.StringFormatter;



/**
 * Class used to generate a Test Plan which check correctness of state transitions by using a State Machine State Graph
 * obtained from parsing a State Machine diagram
 * 
 * When parsing the state machine graph to generate a test plan, it passes a state multiple times but each transition only once for a test path
 * Useful when different transitions use same intermediary state 
 * 
 * Uses Eclipse recommended AST/DOM API which replaced the traditional dom api
 * 
 * __author__ = "TU Wien, Distributed System's Group", http://www.infosys.tuwien.ac.at/
 * __copyright__ = "Copyright 2016, TU Wien, Distributed Systems Group"
 * __license__ = "Apache LICENSE V2.0"
 * __maintainer__ = "Daniel Moldovan"
 * __email__ = "d.moldovan@dsg.tuwien.ac.at"
 */

public class TransitionCorrectnessTestStrategy {
	
	private static final String ASSERT_STATE_LEADING="currentStateIs_";
	private static final String ASSERT_GUARD_LEADING="conditionIsTrue_";
	private static final String FORCE_GUARD_LEADING="setToTrue_";
	private static final String INVOKE_LEADING="invoke_";
	private static final String GENERATE_EVENT_LEADING="generateEvent_";
	private static final String PLAN_METHOD_LEADING="testPlan_";
	
	public static final String TEST_PLAN_CLASS_LEADING="TestPlanForStateMachine";
	
	//cache of generated methods to avoid writing in the generated file the same method twice
	private Map<String,MethodDeclaration> generatedAbstractMethods;
	
	//multiple plan methods an be generated if branching occurs, if-else in state diagram. Then we need to be able to test each flow.
	private Map<String,MethodDeclaration> generatedPlans;
	
	{
		generatedAbstractMethods = new ConcurrentHashMap<>();
		generatedPlans = new ConcurrentHashMap<>();
	}
	
	
	/**
	 * Generates a class to be used in executing the test plan.
	 * The class is abstract because at this point it is unclear how to assert that a certain state has been reached.
	 * Thus, the assertStateReached will be abstract methods.
	 * @param stateGraph
	 */
   public Document generateTestPlan(StateMachineStateGraph stateGraph){
       Document doc = new Document("public abstract class TestPlanForStateMachine" + stateGraph.getStateMachineName() + " { \n" );		 
	  
       //from here we use the cumbersome and extremely detailed Eclipse recommended DOM/AST library
       ASTParser parser = ASTParser.newParser(AST.JLS8);
       parser.setSource(doc.get().toCharArray());
	   CompilationUnit cu = (CompilationUnit) parser.createAST(null);
	   cu.recordModifications();
	   AST ast = cu.getAST();
	   ASTRewrite rewriter = ASTRewrite.create(ast);
	   
	   //create method which will contain one test plan (might be cloned and branched for each if-else in the state machine diagram)    
	   MethodDeclaration testPlanMethodDeclaration = ast.newMethodDeclaration();
	   testPlanMethodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
	   testPlanMethodDeclaration.setName(ast.newSimpleName("testPlan"));
	   testPlanMethodDeclaration.setReturnType2(ast.newPrimitiveType(PrimitiveType.BOOLEAN)); //return true if successful or false otherwise
	   
	   //create method body
       Block testPlanMethodBody = ast.newBlock();
       testPlanMethodDeclaration.setBody(testPlanMethodBody);
       
       //create recursively the test plan by parsing the state graph starting with initial state
       try {
	       generatePlanForState(stateGraph.getInitialState(),rewriter, testPlanMethodDeclaration, new HashSet<StateMachineStateTransition>()).join();
	   } catch (InterruptedException e1) {
		   e1.printStackTrace();
	   } catch (NoSuchStateException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
	       
       
       ListRewrite listRewrite = rewriter.getListRewrite(cu, CompilationUnit.TYPES_PROPERTY);
       
       //add all generated abstract methods
       for (Map.Entry<String, MethodDeclaration> entry: generatedAbstractMethods.entrySet()){
    	   listRewrite.insertLast(entry.getValue(), null);
    	   
       }
       
       int index  = 1;
       //add generated plan methods
       for (Map.Entry<String, MethodDeclaration> entry: generatedPlans.entrySet()){
    	   
    	   //rename to PLAN_METHOD_LEADING + plan index from PLAN_METHOD_LEADING + UUID
    	   MethodDeclaration method = entry.getValue();
    	   method.setName(ast.newSimpleName(PLAN_METHOD_LEADING + index++));
    	   
    	   listRewrite.insertLast(method, null);
       }

       //add final }
	   listRewrite.insertLast(rewriter.createStringPlaceholder("}\n", ASTNode.EMPTY_STATEMENT), null);
       
       TextEdit edits = rewriter.rewriteAST(doc, null);
       try {
           UndoEdit undo = edits.apply(doc);
       } catch (MalformedTreeException | BadLocationException e) {
		   // TODO Auto-generated catch block
		   e.printStackTrace();
	   }

	  
	   System.out.println(doc.get());
	   
	   return doc;
		   		
			   		
   }
	
	/**
	 * 
	 * @param state - the state for which we inspect the transitions and generate the test plan
	 * @param rewrite - rewriter used to modify the generated code
	 * @param planMethodDeclaration - the method in which the code must be added
	 * @param parrentPlanBlock - the block of code where the new state code must be added. Can be a method body, the body of an If statement, etc
	 * @param pathTransitions - used to avoid testing cycles and ensure test plan keeps uniqueness on transitions
	 */
	private Thread generatePlanForState(final StateMachineState state, final ASTRewrite rewrite, final MethodDeclaration planMethodDeclaration, final Set<StateMachineStateTransition> pathTransitions){
		
		Thread thread = new Thread(){
			
			private List<Thread> subThreads = Collections.synchronizedList(new ArrayList<Thread>());

			@Override
			public void run() {
				AST ast = planMethodDeclaration.getAST();
				Block parrentPlanBlock = planMethodDeclaration.getBody();
				
				ListRewrite listRewrite = rewrite.getListRewrite(parrentPlanBlock, Block.STATEMENTS_PROPERTY);
				
				/**
				 * First we create an abstract method to assert that we have reached current state and we call it
				 * only if the state is not a choice. Choices are "virtual" states that signal splits in transition.
				 * 
				 */
				{
					
					//only create method if not previously created
					String stateName = state.getName();
					String methodName = ASSERT_STATE_LEADING + stateName;
					if (!generatedAbstractMethods.containsKey(stateName)){
						MethodDeclaration method = createAbstractMethodForState(state, planMethodDeclaration.getAST());
						generatedAbstractMethods.put(stateName, method);
					}
					
					/**
					 * Call the assert state method to check if we have reached the current state.
					 * For the initial state this assert can also reset the system to initial state.
					 */
					{
						//invoke guard as Assert statement
						AssertStatement  assertStatement = ast.newAssertStatement();
						MethodInvocation invocation  = ast.newMethodInvocation();
						invocation.setName(ast.newSimpleName(methodName));
						assertStatement.setExpression(invocation);
						
						
						parrentPlanBlock.statements().add(assertStatement);
						
//						listRewrite.insertFirst(rewrite.createStringPlaceholder("//Call the assert state method to check if we have reached the current state.", ASTNode.EMPTY_STATEMENT), null);
//						listRewrite.insertLast(rewrite.createStringPlaceholder("//For the initial state this assert can also reset the system to initial state.", ASTNode.EMPTY_STATEMENT), null);
//						listRewrite.insertLast(assertStatement, null);
//						listRewrite.insertLast(rewrite.createStringPlaceholder("", ASTNode.EMPTY_STATEMENT), null);
					}
					
				}
				
			
				/**
				 * If from one state we have multiple triggers, or from a choice we can go to multiple classes
				 * then we generate for each of these transitions paths a separate test plan.
				 * This means we clone the previous method into how many we need
				 */
				 List<StateMachineStateTransition> transitions = state.getOutTransitions();
				 
				 //if 1 transition, then we add to same plan
				 //if more, we need separate test plans for each branching
				 if (transitions.isEmpty() && !(state.getVertex() instanceof FinalState)){
					//notify user that  something is wrong with the model we are converting 
					 notifyUser("State \"" + state.getName() + "\"is not final and does not have any transitions. All state machine flows must reach a FinalState.");
		 			 System.err.println(state.getName() + " is not final and does not have any transitions. All state machine flows must reach a FinalState to be converted in test plans.");
				 }else if (transitions.size() == 1){
					 StateMachineStateTransition transition = transitions.get(0);
					 
					 //if we have visited this transition, continue
					 if (pathTransitions.contains(transition)){
						 return;
					 }else{
						 // add transition to visited transitions
						 pathTransitions.add(transition); 
					 }
					 
//					 listRewrite.insertLast(rewrite.createStringPlaceholder("//Test transition " + transition.getTransition().getName(), ASTNode.EMPTY_STATEMENT), null);
					 
					/**
					 * Must assert before any transition that the guard condition is fulfilled
					 */
					{
						//get transition condition (could also be Rule, currently we get only Guard transitions)
						Constraint guard = transition.getTransition().getGuard();
						if (guard != null) {
							for (Element element : guard.allOwnedElements()) {
								//currently condition retrieved as plain text that will need to be parsed and evaluated
								OpaqueExpression expression = (OpaqueExpression) element;
								for (String body : expression.getBodies()) {
									if (body.isEmpty()){
										notifyUser("Guard condition for transition  " + transition.getTransition().getName() + " from state " + state.getName() + " is empty");
										System.err.println("Guard condition for transition  " + transition.getTransition().getName() + " from state " + state.getName() + " is empty");
										continue;
									}
									MethodDeclaration method = createAbstractMethodForGuard(body, ast);
									if (!generatedAbstractMethods.containsKey(method.getName().toString())){
										generatedAbstractMethods.put(method.getName().toString(), method);
									}
									//invoke guard as Assert statement
									AssertStatement  assertStatement = ast.newAssertStatement();
									MethodInvocation invocation  = ast.newMethodInvocation();
									invocation.setName(ast.newSimpleName(method.getName().toString()));
									assertStatement.setExpression(invocation);
									
									parrentPlanBlock.statements().add(assertStatement);
									
//									listRewrite.insertLast(rewrite.createStringPlaceholder("//Assert guard condition for next transition is true", ASTNode.EMPTY_STATEMENT), null);
//									listRewrite.insertLast(assertStatement, null);
//									listRewrite.insertLast(rewrite.createStringPlaceholder("", ASTNode.EMPTY_STATEMENT), null);
								}
							}
						}
					}
					 
					
					//get all transition triggers
					List<Trigger> triggers  =  transition.getTransition().getTriggers();
					
					//for each trigger
					for (Trigger trigger :triggers) {
						
						/**
						 * If we have not created it already, we create an abstract method to invoke the trigger
						 */
						{
							//TODO: update so we do not generate the trigger if it was already generated
							MethodDeclaration method = createAbstractTriggerInvocation(trigger, planMethodDeclaration.getAST());
							if (!generatedAbstractMethods.containsKey(method.getName().toString())){
								generatedAbstractMethods.put(method.getName().toString(), method);
							}
							//invoke trigger
							MethodInvocation invocation  = ast.newMethodInvocation();
							invocation.setName(ast.newSimpleName(method.getName().toString()));
//							listRewrite.insertLast(rewrite.createStringPlaceholder("//Invoke transition trigger", ASTNode.EMPTY_STATEMENT), null);
//							listRewrite.insertLast(ast.newExpressionStatement(invocation), null);
//							listRewrite.insertLast(rewrite.createStringPlaceholder("", ASTNode.EMPTY_STATEMENT), null);
							
							parrentPlanBlock.statements().add(ast.newExpressionStatement(invocation));

						}
						
					}
					
					if (! (state.getVertex() instanceof FinalState)){
						//continue from target state with plan generation
						
						StateMachineState targetState = transition.getTargetState();
						subThreads.add(generatePlanForState(targetState,rewrite,planMethodDeclaration,pathTransitions));
					}else{
						if (transition.getTargetState() == null){
							 notifyUser(state.getName() + " is not final and does not have a target state on transition " + transition.getTransition().getName());
							 System.err.println(state.getName() + " is not final and does not have a target state on transition " + transition.getTransition().getName());
						}
					}
					
					 
				 }else if (transitions.size() > 1){
					 for(StateMachineStateTransition transition: transitions){
						 
						//clone transitions to use clean path for each sub-trees
						Set<StateMachineStateTransition> transitionsCopy = new HashSet<>();
						transitionsCopy.addAll(pathTransitions);
						
						
						//if we have visited this transition, continue
						 if (transitionsCopy.contains(transition)){
							 continue;
						 }else{
							 // add transition to visited transitions
							 transitionsCopy.add(transition); 
						 } 
						 
						//for each transition we do a clone of the plan until now
						MethodDeclaration transitionMethod = cloneMethodDeclaration(planMethodDeclaration);
						transitionMethod.setName(ast.newSimpleName(PLAN_METHOD_LEADING + (UUID.randomUUID().toString().replaceAll("\\W", ""))));
						
						//shadowing to local parrentPlanBlock
						parrentPlanBlock = transitionMethod.getBody();
						
						//shadowing to local ListRewrite 
//						listRewrite = rewrite.getListRewrite(transitionMethod.getBody(), Block.STATEMENTS_PROPERTY);
//						listRewrite.insertLast(rewrite.createStringPlaceholder("//Forcing transition " + transition.getTransition().getName() + " by ensuring guard conditions are met and triggers are invoked.", ASTNode.EMPTY_STATEMENT), null);
						/**
						 * Must force-set all guard conditions to navigate to this particular execution branch
						 */
						{
							//get transition condition (could also be Rule, currently we get only Guard transitions)
							//force for the current test the transition condition to true, to enable the system to navigate to expected state
							Constraint guard = transition.getTransition().getGuard();
							if (guard != null) {
								for (Element element : guard.allOwnedElements()) {
									//currently condition retrieved as plain text that will need to be parsed and evaluated
									OpaqueExpression expression = (OpaqueExpression) element;
									for (String body : expression.getBodies()) {
										
										if (body.isEmpty()){
											notifyUser("Guard condition for transition  " + transition.getTransition().getName() + " from state " + state.getName() + " is empty");
											System.err.println("Guard condition for transition  " + transition.getTransition().getName() + " from state " + state.getName() + " is empty");
											continue;
										}
										
										MethodDeclaration method = createAbstractForceConditionMethod(body, ast);
										if (!generatedAbstractMethods.containsKey(method.getName().toString())){
											generatedAbstractMethods.put(method.getName().toString(), method);
										}
										//invoke method to force guard condition to true
										MethodInvocation invocation  = ast.newMethodInvocation();
										invocation.setName(ast.newSimpleName(method.getName().toString()));
//										listRewrite.insertLast(rewrite.createStringPlaceholder("//Invoke method to force guard condition to true: " + body, ASTNode.EMPTY_STATEMENT), null);
//										listRewrite.insertLast(ast.newExpressionStatement(invocation), null);
//										listRewrite.insertLast(rewrite.createStringPlaceholder("", ASTNode.EMPTY_STATEMENT), null);
										parrentPlanBlock.statements().add(ast.newExpressionStatement(invocation));
									}
								}
							}
						}
						
						//get all transition triggers and execute them, like if we had only one transition
						List<Trigger> triggers  =  transition.getTransition().getTriggers();
						
						//for each trigger
						for (Trigger trigger :triggers) {
							
							/**
							 * If we have not created it already, we create an abstract method to invoke the trigger
							 */
							{
								//TODO: update so we do not generate the trigger if it was already generated
								MethodDeclaration method = createAbstractTriggerInvocation(trigger, transitionMethod.getAST());
								if (!generatedAbstractMethods.containsKey(method.getName().toString())){
									generatedAbstractMethods.put(method.getName().toString(), method);
								}
								//invoke trigger
								MethodInvocation invocation  = ast.newMethodInvocation();
								invocation.setName(ast.newSimpleName(method.getName().toString()));
//								listRewrite.insertLast(rewrite.createStringPlaceholder("//Invoke transition trigger", ASTNode.EMPTY_STATEMENT), null);
//								listRewrite.insertLast(ast.newExpressionStatement(invocation), null);
//								listRewrite.insertLast(rewrite.createStringPlaceholder("", ASTNode.EMPTY_STATEMENT), null);
								parrentPlanBlock.statements().add(ast.newExpressionStatement(invocation));
							}
							
						}
						
						if (!(state.getVertex() instanceof FinalState)){
							//continue from target state with plan generation
							StateMachineState targetState = transition.getTargetState();
							subThreads.add(generatePlanForState(targetState,rewrite,transitionMethod,transitionsCopy));
						}else{
							if (transition.getTargetState() == null){
								notifyUser(state.getName() + " is not final and does not have a target state on transition " + transition.getTransition().getName());
								System.err.println(state.getName() + " is not final and does not have a target state on transition " + transition.getTransition().getName());
							}
						}
						
						
					 }
					 
				 }
				 
				 if (state.getVertex() instanceof FinalState){
					//store generated method in methods
					generatedPlans.put(planMethodDeclaration.getName().toString(), planMethodDeclaration);
			     }
				 
				 //join on all children threads
				 for (Thread thread: subThreads){
					 try {
						thread.join();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				 }
				 
				 
			}
			
		};
		
		thread.setDaemon(true);
		thread.start();
		return thread;
	}
	
	
//was used for testing DOM things
//	public static void main(String[] args){
//		
//		StateMachineStateGraph stateGraph = new StateMachineStateGraph();
//		stateGraph.setStateMachineName("StateMachineState");
//		
////		DOMFactory domFactory = new DOMFactory();
////
////		//create output content
////		IDOMType planClass = domFactory.createClass();
////		planClass.setName(stateGraph.getStateMachineName());
//		
//		//create the plan as an abstract class
//		Document doc = new Document("public abstract class StateMachineState { \n");		 
//		
//		ASTParser parser = ASTParser.newParser(AST.JLS8);
//		parser.setSource(doc.get().toCharArray());
//		CompilationUnit cu = (CompilationUnit) parser.createAST(null);
//		cu.recordModifications();
//		AST ast = cu.getAST();
//		ASTRewrite rewrite = ASTRewrite.create(ast);
//		
//		//from here we use the cumbersome and extremely detailed Eclipse recommended DOM/AST library
//		
//		MethodDeclaration testPlanMethodDeclaration = ast.newMethodDeclaration();
//		testPlanMethodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
////		testPlanMethodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.ABSTRACT_KEYWORD));
//		testPlanMethodDeclaration.setName(ast.newSimpleName("myMethodName"));
//		testPlanMethodDeclaration.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
//		
//		Block testPlanMethodBody = ast.newBlock();
//		testPlanMethodDeclaration.setBody(testPlanMethodBody);
//
//		ListRewrite listRewrite = rewrite.getListRewrite(testPlanMethodBody, Block.STATEMENTS_PROPERTY);
//		 
//		//invoke guard as Assert statement
//		AssertStatement  assertStatement = ast.newAssertStatement();
//		MethodInvocation invocation  = ast.newMethodInvocation();
//		invocation.setName(ast.newSimpleName("verifyX"));
//		assertStatement.setExpression(invocation);
//		
//		LineComment comment = ast.newLineComment();
//		
//		testPlanMethodBody.statements().add(assertStatement); 
//		
//		 listRewrite = rewrite.getListRewrite(cu, CompilationUnit.TYPES_PROPERTY);
//		 listRewrite.insertLast(testPlanMethodDeclaration, null);
//		 TextEdit edits = rewrite.rewriteAST(doc, null);
//	       try {
//	           UndoEdit undo = edits.apply(doc);
//	       } catch (MalformedTreeException | BadLocationException e) {
//			   // TODO Auto-generated catch block
//			   e.printStackTrace();
//		   }
//		
//		System.out.println(doc.get());
//		
//	}
	
	/**
	 * Method which for any type of trigger Event, from CallEvent, to ChangeEvent, TimeEvent, etc, creates an abstract method
	 * @param trigger
	 * @param ast
	 * @return
	 */
	private MethodDeclaration createAbstractTriggerInvocation(Trigger trigger, AST ast){
		
		//get trigger event
		Event event = trigger.getEvent();
		//if UML operation triggers event (so CLass method)
		
		String methodName = GENERATE_EVENT_LEADING;
		Javadoc javadoc = ast.newJavadoc();
		TagElement tag  = ast.newTagElement();
		TextElement textElement = ast.newTextElement();
		
		if (event instanceof CallEvent){
			
			CallEvent callEvent = (CallEvent) event;
			Operation operation = callEvent.getOperation();
			Class operationClass = (Class) operation.eContainer();
			
			methodName = operationClass.getName().replaceAll("\\W","") + "_" + operation.getName().replaceAll("\\W","");
			textElement.setText("Method must return true if method invocation is successfull."
					+ " Method designed to allow particular implementation  call of \"" + operation.getName()+"\"" + " on class \"" + operationClass.getName() +"\" so we can assert if transition after event is correct");
			
		}else if (event instanceof ChangeEvent){
			
			ChangeEvent changeEvent = (ChangeEvent) event;
			OpaqueExpression changeExpression = (OpaqueExpression) changeEvent.getChangeExpression();
			if (changeExpression.getBodies().isEmpty()){
				System.err.println("Event " + event.getName()+ " has no body/expression");
			}else{
				String body = changeExpression.getBodies().iterator().next();
				body = StringFormatter.convertNonAlphanumericalSymbolsToUnderscore(StringFormatter.convertMathSymbolsToText(body));
				
				methodName = changeEvent.getName().replaceAll("\\W","") + "_" + body;
				textElement.setText("Method must return true if event invocation is successfull"
						+ " Method designed to allow particular implementation  for forcing condition \"" + body + "\" encountered on event \"" + changeEvent.getName() + "\" to true so we can assert if transition after event is correct");
			}
		}else if (event instanceof TimeEvent){
			
			TimeEvent timeEvent = (TimeEvent) event;
			
			if (timeEvent.getWhen() == null || timeEvent.getWhen().getExpr() == null){
				System.err.println("Event " + event.getName()+ " has no when/expression");
			}else{
				String expression = ((LiteralString) timeEvent.getWhen().getExpr()).getValue(); // maybe can do something more with TimeExpression
				expression = StringFormatter.convertNonAlphanumericalSymbolsToUnderscore(StringFormatter.convertMathSymbolsToText(expression));
				
				methodName = timeEvent.getName().replaceAll("\\W","") + "_" + expression;
				textElement.setText("Method must return true if event invocation is successfull"
						+ " Method designed to allow particular implementation  for forcing the time event " + timeEvent.getName() + " with body \"" + expression + "\" to happen so we can assert if transition after event is correct");
			}
			
		}else{
			
			System.err.println("Event type of " + event.getClass() + " not supported yet ");
			methodName = "invoke"+ event.getName().replaceAll("\\W", "_");
			textElement.setText("Event type of " + event.getClass() + " not supported yet so generated javadoc not very usefull. Method must ensure the event is called so we can assert if transition after event is correct.");
		}
		
		tag.fragments().add(textElement);
		javadoc.tags().add(tag);

		MethodDeclaration method = createAbstractAssertMethod(javadoc, methodName, ast);
		return method;
	}
	
	private MethodDeclaration createAbstractMethodForState(StateMachineState state, AST ast){
		
		String stateName = state.equals(StateMachineState.INITIAL_STATE)? "InitialState" : state.getName().replaceAll("\\W","");
		
		Javadoc javadoc = ast.newJavadoc();
		TagElement tag  = ast.newTagElement();
		TextElement textElement = ast.newTextElement();
		textElement.setText("Method must return true if the current state is " + stateName);
		tag.fragments().add(textElement);
		javadoc.tags().add(tag);
		
		MethodDeclaration method = createAbstractAssertMethod(javadoc, ASSERT_STATE_LEADING + stateName, ast);
		return method;
	}
	
	private MethodDeclaration createAbstractMethodForGuard(String condition, AST ast){
		
		String conditionName = StringFormatter.convertNonAlphanumericalSymbolsToUnderscore(StringFormatter.convertMathSymbolsToText(condition));
		
		Javadoc javadoc = ast.newJavadoc();
		TagElement tag  = ast.newTagElement();
		TextElement textElement = ast.newTextElement();
		textElement.setText("Method must evaluate and return true if the following condition is true:  state is " + condition);
		tag.fragments().add(textElement);
		javadoc.tags().add(tag);
	
		MethodDeclaration method = createAbstractAssertMethod(javadoc, ASSERT_GUARD_LEADING + (conditionName.substring(0,1).toUpperCase() + conditionName.substring(1)), ast);
		return method;
	}
	
    private  MethodDeclaration createAbstractForceConditionMethod(String condition, AST ast){
		
		String conditionName =  StringFormatter.convertNonAlphanumericalSymbolsToUnderscore(StringFormatter.convertMathSymbolsToText(condition));
		
		Javadoc javadoc = ast.newJavadoc();
		TagElement tag  = ast.newTagElement();
		TextElement textElement = ast.newTextElement();
		textElement.setText("Method must call tested system and ensure the following condition is true so the test can progress on the current test branch: " + condition);
		tag.fragments().add(textElement);
		javadoc.tags().add(tag);
	
		MethodDeclaration method = createAbstractAssertMethod(javadoc, FORCE_GUARD_LEADING + (conditionName.substring(0,1).toUpperCase() + conditionName.substring(1)), ast);
		return method;
	}
	
	
	/**
	 * Creates abstract methods which return true	
	 * @param javadoc
	 * @param methodName
	 * @param ast
	 * @return an AST method declaration
	 */
	private  MethodDeclaration createAbstractAssertMethod(Javadoc javadoc, String methodName, AST ast){
		
		MethodDeclaration method = ast.newMethodDeclaration();
		
		method.setJavadoc(javadoc);
		
		method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
		method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.ABSTRACT_KEYWORD));
		method.setName(ast.newSimpleName(methodName)); //escape all spaces in state name
		
		/**
		 * add to method generic arguments  Object... arguments
		 */
		SingleVariableDeclaration param  = ast.newSingleVariableDeclaration();
		param.setName(ast.newSimpleName("arguments"));
		param.setType(ast.newSimpleType(ast.newName("Object")));
		param.setStructuralProperty(SingleVariableDeclaration.VARARGS_PROPERTY, true);
		
		method.setReturnType2(ast.newPrimitiveType(PrimitiveType.BOOLEAN));
		
		return method;
	}
	
	
	private MethodDeclaration cloneMethodDeclaration(MethodDeclaration declaration){
		MethodDeclaration clone = (MethodDeclaration)ASTNode.copySubtree(declaration.getAST(), declaration);
		clone.setName(declaration.getAST().newSimpleName(declaration.getName().getIdentifier()+ "_clone"));
		return clone;
    }
	
	/**
	 * Method used to display message boxes with warnings or instructions to users
	 * @param message
	 */
	private void notifyUser(String message){
	   Display.getDefault().syncExec(new Runnable() {
			    public void run() {
		    	MessageDialog.openWarning(Display.getDefault().getActiveShell(), "Warning", message);
		    }
		});
	}
	
}
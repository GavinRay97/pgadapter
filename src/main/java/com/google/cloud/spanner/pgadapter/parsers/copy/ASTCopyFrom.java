/* Generated By:JJTree: Do not edit this line. ASTCopyFrom.java Version 7.0 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.google.cloud.spanner.pgadapter.parsers.copy;

public class ASTCopyFrom extends SimpleNode {
  public ASTCopyFrom(int id) {
    super(id);
  }

  public ASTCopyFrom(Copy p, int id) {
    super(p, id);
  }

  /** Accept the visitor. * */
  public Object jjtAccept(CopyVisitor visitor, Object data) {

    return visitor.visit(this, data);
  }
}
/* JavaCC - OriginalChecksum=e5b4fa03ba34add156f9bf899d3297d5 (do not edit this line) */

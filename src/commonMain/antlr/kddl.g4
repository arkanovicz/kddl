grammar kddl;

//@header {
//package com.republicate.kddl.grammar;
//}

database: 'database' name=LABEL '{' ( schema | link ) * '}' EOF ;
schema: 'schema' name=LABEL '{' ( table | link )* '}' ;
table: 'table' name=LABEL ( ':' parent direction? )? '{' field* '}' ;
parent: ( parent_schema=LABEL '.' )? name=LABEL ;
direction: '(' ( 'up' | 'down' | 'left' | 'right' ) ')'  ;
link: (( left_schema=LABEL '.' )? left=LABEL) (left_mult='*' | left_single='<')? '-'+ (right_mult='*' | right_single='>')? (( LABEL '.' )? LABEL) ;
field: ( pk='*' | unique='!' )? name=LABEL (type ( optional='?' )? default? | ( optional='?' )? ARROW ) ( ref_schema=LABEL '.' )? reference=LABEL ;
// prefix: pk='*' | fk='+' | unique='!' ;
type: 'boolean'
    | 'integer'
    | 'int'
    | 'serial'
    | 'long'
    | 'float'
    | 'double'
    | 'money'
    | 'numeric' '(' prec=INTEGER ( ',' scale=INTEGER )? ')'
    | 'time'
    | 'date'
    | 'datetz'
    | 'datetime'
    | 'datetimetz'
    | 'interval'
    | 'char' ( '(' width=INTEGER ')' )?
    | 'varchar' '(' width=INTEGER ')'
    | 'text'
    | 'enum(' value=STRING ( ','? value=STRING )* ')'
    | 'json'
    | 'jsonb' ;
default: '=' expression ;
expression: NULL | boolean | number | STRING | function ;
boolean: 'true' | 'false' ;
number: INTEGER ( '.' INTEGER )? ;
STRING: '\'' .*? '\'' ;
NULL: 'null' ;
function: LABEL '(' .*? ')';

LABEL: [azAZ_][azAZ0-9_]* ;
// LABEL: NameCharStart NameChar* ;
INTEGER: [0-9]+ ;
ARROW: '-' + '>' ;

WS: [ \t\n\r]+ -> skip ;
LINE_COMMENT : '//' .*? '\n' -> skip ;
BLOCK_COMMENT:'/*' .*? '*/' -> skip ;

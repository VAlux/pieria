; Pieria Java tag query. Definitions are captured as @def.<kind> with the name node as @def.name;
; relations as @ref.<relation> with the target type/name node as @ref.name. The parser
; (TreeSitterCodeParser) computes qualified names and enclosing context by walking the node tree, so
; this query only needs to surface the declaration/reference nodes and their names.

; --- package ---
(package_declaration [(identifier) (scoped_identifier)] @def.name) @def.package

; --- type declarations ---
(class_declaration name: (identifier) @def.name) @def.class
(interface_declaration name: (identifier) @def.name) @def.interface
(enum_declaration name: (identifier) @def.name) @def.enum
(record_declaration name: (identifier) @def.name) @def.record

; --- members ---
(method_declaration name: (identifier) @def.name) @def.method
(constructor_declaration name: (identifier) @def.name) @def.constructor
(field_declaration declarator: (variable_declarator name: (identifier) @def.name)) @def.field

; --- relations ---
(superclass (_) @ref.name) @ref.extends
(super_interfaces (type_list (_) @ref.name)) @ref.implements
(method_invocation name: (identifier) @ref.name) @ref.call

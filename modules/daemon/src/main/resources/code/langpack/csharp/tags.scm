; C# definitions and relations.
(namespace_declaration name: (_) @def.name) @def.package
(file_scoped_namespace_declaration name: (_) @def.name) @def.package
(class_declaration name: (identifier) @def.name) @def.class
(record_declaration name: (identifier) @def.name) @def.class
(struct_declaration name: (identifier) @def.name) @def.class
(interface_declaration name: (identifier) @def.name) @def.interface
(enum_declaration name: (identifier) @def.name) @def.enum
(delegate_declaration name: (identifier) @def.name) @def.type_alias
(method_declaration name: (identifier) @def.name) @def.method
(constructor_declaration name: (identifier) @def.name) @def.method
(local_function_statement name: (identifier) @def.name) @def.function
(field_declaration (variable_declaration (variable_declarator name: (identifier) @def.name))) @def.field
(property_declaration name: (identifier) @def.name) @def.field

(using_directive (_) @ref.name) @ref.import
(class_declaration (base_list (_) @ref.name)) @ref.extends
(record_declaration (base_list (_) @ref.name)) @ref.extends
(struct_declaration (base_list (_) @ref.name)) @ref.implements
(interface_declaration (base_list (_) @ref.name)) @ref.extends
(invocation_expression function: (identifier) @ref.name) @ref.call
(invocation_expression function: (member_access_expression name: (identifier) @ref.name)) @ref.call

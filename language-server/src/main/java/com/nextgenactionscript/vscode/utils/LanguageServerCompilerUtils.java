/*
Copyright 2016-2018 Bowler Hat LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.nextgenactionscript.vscode.utils;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.apache.royale.compiler.clients.problems.CompilerProblemCategorizer;
import org.apache.royale.compiler.common.ISourceLocation;
import org.apache.royale.compiler.definitions.IAccessorDefinition;
import org.apache.royale.compiler.definitions.IClassDefinition;
import org.apache.royale.compiler.definitions.IDefinition;
import org.apache.royale.compiler.definitions.IEventDefinition;
import org.apache.royale.compiler.definitions.IFunctionDefinition;
import org.apache.royale.compiler.definitions.IInterfaceDefinition;
import org.apache.royale.compiler.definitions.IStyleDefinition;
import org.apache.royale.compiler.definitions.ITypeDefinition;
import org.apache.royale.compiler.definitions.IVariableDefinition;
import org.apache.royale.compiler.problems.CompilerProblemSeverity;
import org.apache.royale.compiler.problems.ICompilerProblem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Utility functions for converting between language server types and Flex
 * compiler types.
 */
public class LanguageServerCompilerUtils
{
    /**
     * Converts an URI from the language server protocol to a Path.
     */
    public static Path getPathFromLanguageServerURI(String apiURI)
    {
        URI uri = URI.create(apiURI);
        Optional<Path> optionalPath = getFilePath(uri);
        if (!optionalPath.isPresent())
        {
            System.err.println("Could not find URI " + uri);
            return null;
        }
        return optionalPath.get();
    }

    /**
     * Converts a compiler problem to a language server severity.
     */
    public static DiagnosticSeverity getDiagnosticSeverityFromCompilerProblem(ICompilerProblem problem)
    {
        CompilerProblemCategorizer categorizer = new CompilerProblemCategorizer(null);
        CompilerProblemSeverity severity = categorizer.getProblemSeverity(problem);
        switch (severity)
        {
            case ERROR:
            {
                return DiagnosticSeverity.Error;
            }
            case WARNING:
            {
                return DiagnosticSeverity.Warning;
            }
            default:
            {
                return DiagnosticSeverity.Information;
            }
        }
    }

    /**
     * Converts a compiler source location to a language server location. May
     * return null if the line or column of the source location is -1.
     */
    public static Location getLocationFromSourceLocation(ISourceLocation sourceLocation)
    {
        Path sourceLocationPath = Paths.get(sourceLocation.getSourcePath());
        Location location = new Location();
        location.setUri(sourceLocationPath.toUri().toString());

        Range range = getRangeFromSourceLocation(sourceLocation);
        if (range == null)
        {
            //this is probably generated by the compiler somehow
            return null;
        }
        location.setRange(range);

        return location;
    }

    /**
     * Converts a compiler source location to a language server range. May
     * return null if the line or column of the source location is -1.
     */
    public static Range getRangeFromSourceLocation(ISourceLocation sourceLocation)
    {
        int line = sourceLocation.getLine();
        int column = sourceLocation.getColumn();
        if (line == -1 || column == -1)
        {
            //this is probably generated by the compiler somehow
            return null;
        }
        Position start = new Position();
        start.setLine(line);
        start.setCharacter(column);

        int endLine = sourceLocation.getEndLine();
        int endColumn = sourceLocation.getEndColumn();
        if (endLine == -1 || endColumn == -1)
        {
            endLine = line;
            endColumn = column;
        }
        Position end = new Position();
        end.setLine(endLine);
        end.setCharacter(endColumn);

        Range range = new Range();
        range.setStart(start);
        range.setEnd(end);

        return range;
    }
    
    /**
     * Converts the absolute character offset to a language server position.
     */
    public static Position getPositionFromOffset(Reader in, int targetOffset)
    {
        return getPositionFromOffset(in, targetOffset, new Position());
    }

    public static Position getPositionFromOffset(Reader in, int targetOffset, Position result)
    {
        try
        {
            int offset = 0;
            int line = 0;
            int character = 0;

            while (offset < targetOffset)
            {
                int next = in.read();

                if (next < 0)
                {
                    result.setLine(line);
                    result.setCharacter(line);
                    return result;
                }
                else
                {
                    offset++;
                    character++;

                    if (next == '\n')
                    {
                        line++;
                        character = 0;
                    }
                }
            }

            result.setLine(line);
            result.setCharacter(character);
        }
        catch (IOException e)
        {
            result.setLine(-1);
            result.setCharacter(-1);
        }
        return result;
    }
    
    /**
     * Converts a language server position to the absolute character offset.
     */
    public static int getOffsetFromPosition(Reader in, Position position)
    {
        int targetLine = position.getLine();
        int targetCharacter = position.getCharacter();
        try
        {
            int offset = 0;
            int line = 0;
            int character = 0;

            while (line < targetLine)
            {
                int next = in.read();

                if (next < 0)
                {
                    return offset;
                }
                else
                {
                    //don't skip \r here if line endings are \r\n in the file
                    //there may be cases where the file line endings don't match
                    //what the editor ends up rendering. skipping \r will help
                    //that, but it will break other cases.
                    offset++;

                    if (next == '\n')
                    {
                        line++;
                    }
                }
            }

            while (character < targetCharacter)
            {
                int next = in.read();

                if (next < 0)
                {
                    return offset;
                }
                else
                {
                    offset++;
                    character++;
                }
            }

            return offset;
        }
        catch (IOException e)
        {
            return -1;
        }
    }

    private static Optional<Path> getFilePath(URI uri)
    {
        if (!uri.getScheme().equals("file"))
        {
            return Optional.empty();
        }
        else
        {
            return Optional.of(Paths.get(uri));
        }
    }

    public static CompletionItemKind getCompletionItemKindFromDefinition(IDefinition definition)
    {
        if (definition instanceof IClassDefinition)
        {
            return CompletionItemKind.Class;
        }
        else if (definition instanceof IInterfaceDefinition)
        {
            return CompletionItemKind.Interface;
        }
        else if (definition instanceof IAccessorDefinition)
        {
            return CompletionItemKind.Field;
        }
        else if (definition instanceof IFunctionDefinition)
        {
            IFunctionDefinition functionDefinition = (IFunctionDefinition) definition;
            if (functionDefinition.isConstructor())
            {
                return CompletionItemKind.Constructor;
            }
            IDefinition parentDefinition = functionDefinition.getParent();
            if (parentDefinition != null && parentDefinition instanceof ITypeDefinition)
            {
                return CompletionItemKind.Method;
            }
            return CompletionItemKind.Function;
        }
        else if (definition instanceof IVariableDefinition)
        {
            IVariableDefinition variableDefinition = (IVariableDefinition) definition;
            switch(variableDefinition.getVariableClassification())
            {
                case INTERFACE_MEMBER:
                case CLASS_MEMBER:
                {
                    return CompletionItemKind.Field;
                }
                default:
                {
                    return CompletionItemKind.Variable;
                }
            }
        }
        else if (definition instanceof IEventDefinition)
        {
            return CompletionItemKind.Event;
        }
        else if (definition instanceof IStyleDefinition)
        {
            return CompletionItemKind.Field;
        }
        return CompletionItemKind.Value;
    }

    public static Diagnostic getDiagnosticFromCompilerProblem(ICompilerProblem problem)
    {
        Diagnostic diagnostic = new Diagnostic();

        DiagnosticSeverity severity = LanguageServerCompilerUtils.getDiagnosticSeverityFromCompilerProblem(problem);
        diagnostic.setSeverity(severity);

        Range range = LanguageServerCompilerUtils.getRangeFromSourceLocation(problem);
        if (range == null)
        {
            //fall back to an empty range
            range = new Range(new Position(), new Position());
        }
        diagnostic.setRange(range);

        diagnostic.setMessage(problem.toString());

        try
        {
            Field field = problem.getClass().getDeclaredField("errorCode");
            int errorCode = (int) field.get(problem);
            diagnostic.setCode(Integer.toString(errorCode));
        }
        catch (Exception e)
        {
            //skip it
        }
        return diagnostic;
    }
}
